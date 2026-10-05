# 1. Users, groups and roles

This guide explains who can do what in the application and how to change it, both in the Keycloak
web console (point and click) and with scripts (repeatable, good for many users).

## 1.1 How access works (read this first)

* **Keycloak** is the login service. It keeps the list of users and checks passwords (or hands the
  login to Google).
* All application users live in the Keycloak **realm** called **`ticketing`**. (A realm is an isolated
  set of users. There is also a realm called `master`: it is only for Keycloak administrators. Never
  create application users there.)
* **Roles are given through groups.** Each tenant (customer organisation) has a group with two
  sub-groups:

  ```
  /acme                 <- the tenant. Being in this group alone gives NO access.
  /acme/applicant       <- may raise tickets in acme and see their own tickets
  /acme/approver        <- may see all acme tickets, pick them up, approve/reject/ask for details
  /globex
  /globex/applicant
  /globex/approver
  ```

* A person can be in several groups, so they can have different roles in different tenants. A person
  who is both applicant and approver in the same tenant can never approve **their own** tickets
  (separation of duties).
* When a user signs in, Keycloak writes their groups into the login token. The application reads the
  roles from that token. **Changes therefore take effect only after the user signs out and signs in
  again** (or after at most 5 minutes, when the token is renewed).
* A **tenant** must exist in two places: as Keycloak groups (who belongs to it) **and** as a row in the
  PostgreSQL `tenant` table (that the tenant is registered and active). See [section 1.6](#16-add-a-new-tenant).

### Kinds of users

| Kind | Created by | Signs in with |
|---|---|---|
| Local user (e.g. `alice`) | an administrator, in Keycloak | username/email + password kept by Keycloak |
| Google user | automatically, the first time the person clicks "Sign in with Google" | their Google account (Keycloak never sees the Google password) |

Google users start with **no groups**, so after their first sign-in the app shows *"Your account is not
assigned to any tenant yet"* until an administrator gives them a role.

---

## 1.2 Open the Keycloak admin console

1. Open **https://ticketing.localtest.me:8443/auth/admin** in a browser.
2. The browser warns about the certificate (it is issued by our private, self-signed authority).
   Choose *Advanced* → *Continue*.
3. Sign in as `admin`. The password is in `k8s/kustomization.yaml` (`KC_BOOTSTRAP_ADMIN_PASSWORD`).
4. **Switch realm:** at the top of the left sidebar there is a drop-down showing *Keycloak* or
   *master*. Open it and select **ticketing**. Everything below happens in this realm.

---

## 1.3 Give an existing user a role (console)

1. Left menu → **Users**. Type part of the email in the search box and press Enter.
2. Click the username.
3. Open the **Groups** tab → **Join Group**.
4. Click the **>** arrow next to the tenant (for example `acme`) to see its sub-groups.
5. Tick **applicant** and/or **approver** (not the tenant itself) → **Join**.
6. The Groups tab now shows a path such as `/acme/applicant`.
7. Ask the user to **sign out and sign in again**.

**Remove a role:** same tab, tick the group, click **Leave**.

## 1.4 Create a local user (console)

1. **Users** → **Add user** (or **Create new user**).
2. Fill in **Username**, **Email**, **First name**, **Last name**. Switch **Email verified** to *On*
   (otherwise the user is asked to verify an email address and no mail server is configured).
3. Optionally click **Join groups** and pick the role groups right away. → **Create**.
4. Open the **Credentials** tab → **Set password**. Type the password twice. Switch **Temporary** to
   *On* if the user must choose a new password at first sign-in. → **Save**.

**Disable a user** (blocks sign-in, keeps their history): open the user → switch **Enabled** off → Save.
**Reset a password:** user → **Credentials** → **Reset password**.

> Do **not** delete users who have raised or decided tickets: the tickets keep their email as owner
> and history. Disable them instead.

---

## 1.5 The same tasks with scripts

Scripts are faster for many users and leave a record of what was done. Start every session with the
steps in [Before you start](README.md#before-you-start-do-this-once-per-terminal-window).

### Give a role (the simplest way)

```bash
bash scripts/add-role.sh <email-or-username> <tenant> <applicant|approver>

# examples
bash scripts/add-role.sh ravik775@gmail.com acme applicant
bash scripts/add-role.sh carol globex approver
```

Expected output:

```
'ravik775@gmail.com' is now applicant in acme. Groups: /acme/applicant
Sign out and sign in again for the new role to take effect.
```

The script refuses unknown users ("a Google user must sign in once first"), unknown groups and roles
other than `applicant`/`approver`. Running it twice is harmless.

### Other user tasks with the Keycloak admin CLI (`kcadm`)

`kcadm.sh` is Keycloak's own command-line tool. It runs **inside** the Keycloak pod, so we define a
short helper first (copy all lines at once):

```bash
KC() { MSYS_NO_PATHCONV=1 kubectl -n auth exec deploy/keycloak -- /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config; }
ADMIN_PW=$(kubectl -n auth get secret keycloak-env -o jsonpath='{.data.KC_BOOTSTRAP_ADMIN_PASSWORD}' | base64 -d)
KC config credentials --server http://localhost:8080/auth --realm master --user admin --password "$ADMIN_PW"
```

The last line logs the CLI in (answer: `Logging into http://localhost:8080/auth as user admin of realm master`).
The login lasts a while; if a later command says `Session has expired`, run that line again.

| Task | Command |
|---|---|
| Create a local user | `KC create users -r ticketing -s username=frank -s email=frank@example.com -s emailVerified=true -s enabled=true -s firstName=Frank -s lastName=Smith` |
| Set their password | `KC set-password -r ticketing --username frank --new-password 'S0me-Strong!Pass'` (add `--temporary` to force a change at first login) |
| Give a role | `bash scripts/add-role.sh frank@example.com acme applicant` |
| List users | `KC get users -r ticketing --fields username,email,enabled` |
| Show one user's groups | `ID=$(KC get users -r ticketing -q username=frank -q exact=true --fields id --format csv --noquotes); KC get users/$ID/groups -r ticketing --fields path` |
| Remove a role | `GID=$(KC get group-by-path/acme/applicant -r ticketing --fields id --format csv --noquotes); KC delete users/$ID/groups/$GID -r ticketing` |
| Disable a user | `KC update users/$ID -r ticketing -s enabled=false` |
| Is the user a Google user? | `KC get users/$ID/federated-identity -r ticketing` (shows `"identityProvider" : "google"`) |

(`$ID` and `$GID` are set by the line before them; run the lines in order.)

---

## 1.6 Add a new tenant

A tenant needs **both** of these steps. Example: a new customer `initech`.

**Step 1 – Keycloak groups** (console: **Groups** → **Create group** `initech`; then open it →
**Child groups** → **Create child group** `applicant`, and again `approver`). Or with the CLI:

```bash
GID=$(KC create groups -r ticketing -s name=initech -i)
KC create groups/$GID/children -r ticketing -s name=applicant
KC create groups/$GID/children -r ticketing -s name=approver
```

The names must be exactly `applicant` and `approver` (lower case): the application recognises a role
only from a group path `/<tenant>/applicant` or `/<tenant>/approver`.

**Step 2 – register the tenant in the database:**

```bash
kubectl -n ticketing exec postgres-0 -- psql -U postgres -d ticketing \
  -c "INSERT INTO tenant (id, name) VALUES ('initech', 'Initech Ltd');"
```

Expected: `INSERT 0 1`. The `id` must be identical to the Keycloak group name.

If you skip step 2, users can sign in and see the tenant, but raising a ticket fails with
*"Tenant 'initech' is not registered or not active"*.

**Suspend a tenant** (nobody can raise new tickets, existing data stays):

```bash
kubectl -n ticketing exec postgres-0 -- psql -U postgres -d ticketing \
  -c "UPDATE tenant SET active = false WHERE id = 'initech';"
```

> Tenants that must exist on every fresh installation belong in a database migration file
> (`ticket-core/src/main/resources/db/migration/`, e.g. `V4__add_initech.sql`) and in
> `k8s/keycloak/realm-ticketing.json`, so that `scripts/up.sh` creates them automatically.

---

## 1.7 Troubleshooting

| What the user sees | Cause | Fix |
|---|---|---|
| "Your account is not assigned to any tenant yet" | user has no `/<tenant>/<role>` group | give a role (1.3 or 1.5), then sign out/in |
| Role was added but nothing changed | the old token is still in use | sign out and in again |
| The tenant appears but buttons are missing | user has only one of the two roles | add the other role if needed |
| "Approvers cannot act on tickets they raised themselves" | separation of duties | another approver must handle it (working as designed) |
| "Tenant '…' is not registered or not active" | tenant missing/disabled in PostgreSQL | section 1.6 step 2 |
| Google button shows a username/password form | Google sign-in not configured | `bash scripts/set-google.sh <client-id> <client-secret>` (see main README) |
| `kcadm` says `Session has expired` | CLI login timed out | run the `KC config credentials …` line again |
| `stat C:/Program Files/Git/opt/keycloak/...: no such file` | Git Bash rewrote the path | keep the `MSYS_NO_PATHCONV=1` prefix (it is inside the `KC` helper) |
