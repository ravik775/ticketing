#!/usr/bin/env bash
# Restore drill: proves the backups can actually be restored, to a point in time, without touching the
# live databases. It restores the ONE backup copy into a throw-away pod (no network access), rolls it
# forward to the target time, compares the restored data with the live database, and deletes the pod.
#
#   Usage: scripts/restore-drill.sh [postgres|mongo|all] [target-time]
#     target-time: "now" (default) or a UTC time like "2026-10-05 08:15:00"
#
# Local RPO is <= 5 minutes (PostgreSQL archive_timeout, MongoDB oplog slice interval). A drill to "now"
# first forces the latest WAL / oplog into the backup so the comparison is exact.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash: keep in-container paths (/backup, /restore) untouched
WHAT="${1:-all}"
TARGET="${2:-now}"
NS=ticketing
k() { kubectl -n "$NS" "$@"; }

start_pod() {   # name image pvc
  k delete pod "$1" --ignore-not-found --wait=true >/dev/null
  k apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: $1
  namespace: $NS
  labels: {app: restore-drill}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 999, runAsGroup: 999, fsGroup: 999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: restore
      image: $2
      command: [sleep, "3600"]
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: [ALL]}}
      resources: {requests: {cpu: 50m, memory: 128Mi}, limits: {memory: 768Mi}}
      volumeMounts:
        - {name: backup, mountPath: /backup, readOnly: true}
        - {name: restore, mountPath: /restore}
  volumes:
    - {name: backup, persistentVolumeClaim: {claimName: $3, readOnly: true}}
    - {name: restore, emptyDir: {}}
YAML
  k wait --for=condition=Ready "pod/$1" --timeout=180s >/dev/null
}

drill_postgres() {
  echo "== PostgreSQL restore drill"
  local target="$TARGET" live
  if [ "$target" = now ]; then
    target=$(k exec postgres-0 -c postgres -- psql -U postgres -tAc "select to_char(now() at time zone 'utc', 'YYYY-MM-DD HH24:MI:SS')")
  fi
  live=$(k exec postgres-0 -c postgres -- psql -U postgres -d ticketing -tAc \
    "select count(*) from ticket_workflow where created_at <= '$target+00'")
  # Recovery to a time stops at the first commit AFTER it: make sure one exists (a commit with no data change),
  # then close the WAL segment so it is archived.
  sleep 1
  k exec postgres-0 -c postgres -- psql -U postgres -tAc "select txid_current()" >/dev/null
  k exec postgres-0 -c postgres -- psql -U postgres -tAc "select pg_switch_wal()" >/dev/null
  sleep 8
  echo "   base backup taken at: $(k exec postgres-0 -c backup -- cat /backup/base/current/TAKEN_AT)"
  echo "   target time (UTC):    $target"
  start_pod pg-restore-drill postgres:16 postgres-backup
  k exec pg-restore-drill -- sh -c "
    set -e; D=/restore/pgdata; mkdir -p \$D; chmod 700 \$D
    tar -xzf /backup/base/current/base.tar.gz -C \$D
    tar -xzf /backup/base/current/pg_wal.tar.gz -C \$D/pg_wal
    touch \$D/recovery.signal
    printf \"%s\n\" \"restore_command = 'test -f /backup/wal/%f.gz && gunzip -c /backup/wal/%f.gz > %p'\" \
      \"recovery_target_time = '$target+00'\" \"recovery_target_action = 'promote'\" >> \$D/postgresql.auto.conf
    (postgres -D \$D -c listen_addresses='' -c archive_mode=off -c unix_socket_directories=/restore > /restore/pg.log 2>&1 &)
    for i in \$(seq 1 90); do
      if pg_isready -h /restore -q && [ \"\$(psql -h /restore -U postgres -tAc 'select pg_is_in_recovery()')\" = f ]; then exit 0; fi
      sleep 2
    done
    tail -20 /restore/pg.log; exit 1"
  local restored
  restored=$(k exec pg-restore-drill -- psql -h /restore -U postgres -d ticketing -tAc "select count(*) from ticket_workflow")
  echo "   recovery log: $(k exec pg-restore-drill -- sh -c "grep -E 'recovery stopping|redo done|selected new timeline' /restore/pg.log | tail -2 | tr '\n' ' '")"
  echo "   tickets at target: live=$live restored=$restored"
  k delete pod pg-restore-drill --wait=false >/dev/null
  [ "$live" = "$restored" ] && echo "   PASS PostgreSQL point-in-time restore" || { echo "   FAIL PostgreSQL restore mismatch"; return 1; }
}

drill_mongo() {
  echo "== MongoDB restore drill"
  local target_epoch live
  if [ "$TARGET" = now ]; then target_epoch=$(date -u +%s); else target_epoch=$(date -u -d "$TARGET" +%s); fi
  live=$(k exec mongo-0 -c mongo -- sh -c "mongosh --quiet -u ticketing_app -p \"\$MONGO_APP_PASSWORD\" --authenticationDatabase ticketing ticketing \
    --eval 'db.ticket_details.countDocuments({createdAt: {\$lte: new Date($target_epoch * 1000)}})'")
  # force a fresh oplog slice so a drill to "now" is exact (the agent normally slices every 5 minutes)
  k exec mongo-0 -c backup -- sh -c '
    URI="mongodb://${MONGO_INITDB_ROOT_USERNAME}:${MONGO_INITDB_ROOT_PASSWORD}@localhost:27017/?authSource=admin&directConnection=true"
    from=$(cat /backup/oplog/LAST_TS)
    to=$(mongosh --quiet "$URI" --eval "const d=db.getSiblingDB(\"local\").oplog.rs.find().sort({\$natural:-1}).limit(1).next(); print(d.ts.t + \":\" + d.ts.i)")
    [ "$from" = "$to" ] && exit 0
    q="{\"ts\":{\"\$gt\":{\"\$timestamp\":{\"t\":${from%%:*},\"i\":${from##*:}}},\"\$lte\":{\"\$timestamp\":{\"t\":${to%%:*},\"i\":${to##*:}}}}}"
    mongodump --uri "$URI" -d local -c oplog.rs --query "$q" --out "/backup/oplog/slice-$(printf %012d ${to%%:*})-${to##*:}" --quiet && echo "$to" > /backup/oplog/LAST_TS'
  echo "   full dump taken at:   $(k exec mongo-0 -c backup -- cat /backup/base/TAKEN_AT)"
  echo "   oplog slices:         $(k exec mongo-0 -c backup -- sh -c 'ls -d /backup/oplog/slice-* 2>/dev/null | wc -l')"
  echo "   target time (UTC):    $(date -u -d "@$target_epoch" '+%F %T')"
  start_pod mongo-restore-drill mongo:7 mongo-backup
  k exec mongo-restore-drill -- sh -c "
    set -e; mkdir -p /restore/db
    (mongod --dbpath /restore/db --bind_ip 127.0.0.1 --port 27018 --wiredTigerCacheSizeGB 0.25 > /restore/mongod.log 2>&1 &)
    for i in \$(seq 1 60); do mongosh --quiet --port 27018 --eval 'db.adminCommand({ping:1}).ok' >/dev/null 2>&1 && break; sleep 2; done
    mongorestore --port 27018 --gzip --archive=/backup/base/current.archive.gz --oplogReplay --quiet
    for s in \$(ls -d /backup/oplog/slice-* 2>/dev/null | sort); do
      rm -rf /restore/replay; mkdir -p /restore/replay
      cp \$s/local/oplog.rs.bson /restore/replay/oplog.bson
      mongorestore --port 27018 --oplogReplay --oplogLimit $target_epoch:0 /restore/replay --quiet 2>/dev/null || true
    done"
  local restored
  restored=$(k exec mongo-restore-drill -- mongosh --quiet --port 27018 ticketing --eval 'db.ticket_details.countDocuments({})')
  echo "   documents at target: live=$live restored=$restored"
  k delete pod mongo-restore-drill --wait=false >/dev/null
  [ "$live" = "$restored" ] && echo "   PASS MongoDB point-in-time restore" || { echo "   FAIL MongoDB restore mismatch"; return 1; }
}

rc=0
case "$WHAT" in
  postgres) drill_postgres || rc=1 ;;
  mongo)    drill_mongo || rc=1 ;;
  all)      drill_postgres || rc=1; drill_mongo || rc=1 ;;
  *) echo "usage: $0 [postgres|mongo|all] [target-time]" >&2; exit 2 ;;
esac
exit $rc
