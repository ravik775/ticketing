# Ticketing platform: operations handbook

This folder explains how the ticketing application is built and how to look after it. It is written
for someone who has **never used these tools before**: every command is given in full, with what it
does and what you should expect to see.

| # | Guide | Read it when you need to… |
|---|---|---|
| 1 | [Users, groups and roles](01-users-and-groups.md) | give someone access, change a role, add a tenant |
| 2 | [Logs, metrics, traces and events](02-observability.md) | find out what happened to a request, check health and load |
| 3 | [Investigating the databases](03-database-investigation.md) | look at ticket data in PostgreSQL and MongoDB |
| 4 | [Architecture](04-architecture.md) | understand what runs where and how the parts talk |
| 5 | [mTLS and certificate rotation](05-mtls-and-certificate-rotation.md) | understand service-to-service TLS, renew or replace certificates |
| 6 | [OpenSSL certificate investigation](06-openssl-certificate-investigation.md) | check a certificate, its expiry, or its chain of trust |
| 7 | [Hosting on AWS](07-aws-deployment.md) | move the platform to AWS with a public (trusted) certificate |
| 8 | [Security and Zero Trust](08-security-zero-trust.md) | understand every security control and why it exists |
| 9 | [Web Application Firewall](09-waf-firewall.md) | understand, test and tune the firewall in front of the gateway |
| 10 | [MCP endpoint for AI agents](10-mcp-integration.md) | connect an AI assistant, understand how agent actions are secured and audited |
| – | [Enterprise gap assessment](enterprise-gap.md) | see how the platform scores against enterprise production, what was fixed and what is still open |

---

## Before you start (do this once per terminal window)

### 1. Open the right terminal

On Windows, use **Git Bash** (Start menu → "Git Bash"). All commands in these guides are written for
Git Bash (they also work unchanged on macOS/Linux terminals). PowerShell and `cmd` use a different
syntax and will not work with the commands as written.

> **Tip:** copy a command, then paste it into Git Bash with **Shift + Insert** (or right-click → Paste).
> Lines that start with `#` are comments: you do not need to type them.

### 2. Go to the project folder and make the tools available

```bash
cd /c/learn/Kongp3                 # the folder that contains this docs/ folder
export PATH="$HOME/bin:$PATH"      # makes the k3d tool (installed in ~/bin) available
kubectl config use-context k3d-ticketing
```

`kubectl config use-context` should answer `Switched to context "k3d-ticketing".` From now on every
`kubectl` command talks to the local cluster.

### 3. Check that everything is running

```bash
kubectl get pods -A
```

Every line should show `Running` and `1/1` in the READY column. If Docker Desktop was closed, start it,
wait until its whale icon stops animating, and run the command again (the cluster starts with Docker).

### 4. A note about Git Bash and paths inside containers

Git Bash quietly rewrites anything that looks like a Unix path (`/opt/...`) into a Windows path before
handing it to a program. That breaks commands that run **inside** a container. When a guide shows a
command prefixed with `MSYS_NO_PATHCONV=1`, keep that prefix; it switches the rewriting off for that
one command. Do **not** `export` it for the whole session: other commands (such as
`scripts/render-kong.sh`) need the rewriting for files on your own disk.

---

## Everyday commands at a glance

| Task | Command |
|---|---|
| Start everything from scratch | `bash scripts/up.sh` |
| Run the full automatic test of the running system | `bash scripts/e2e.sh` (should end with `0 failed`) |
| Stop and delete the whole cluster | `bash scripts/down.sh` |
| See all running parts | `kubectl get pods -A` |
| Read a component's log | `kubectl -n <namespace> logs deploy/<name>` |
| Give a user a role | `bash scripts/add-role.sh <email> <tenant> <applicant\|approver>` |
| Re-load the gateway configuration | `bash scripts/render-kong.sh` |

Application URL: **https://ticketing.localtest.me:8443**. Keycloak admin console: only through a tunnel,
`kubectl -n auth port-forward svc/keycloak 9443:8443` then **https://localhost:9443/auth/admin/** (guide 1.2).
Database and admin passwords are random and live in OpenBao (guide 8.6); demo end-user passwords are in the
main [README](../README.md).

## Glossary

| Word | Meaning |
|---|---|
| **Pod** | One running copy of a program inside Kubernetes (like a small virtual machine). |
| **Deployment / StatefulSet** | The instruction "keep N copies of this pod running"; StatefulSet is used for databases. |
| **Namespace** | A folder that groups related pods: `edge`, `gateway`, `auth`, `ticketing`. |
| **Service** | A stable network name for a set of pods, e.g. `ticket-service.ticketing.svc.cluster.local`. |
| **Secret** | Kubernetes storage for passwords, keys and certificates. |
| **kubectl** | The command-line tool that talks to Kubernetes. |
| **k3d / k3s** | A small Kubernetes that runs inside Docker on this laptop. |
| **Tenant** | A customer organisation (`acme`, `globex`); its data is strictly separated from other tenants. |
| **JWT / token** | The signed "pass" a user receives from Keycloak after signing in; sent with every API call. |
| **TLS / mTLS** | Encrypted connections; in *mutual* TLS both sides prove their identity with certificates. |
| **WAF** | Web Application Firewall: inspects each web request and blocks attacks. |
