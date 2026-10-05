FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY default.conf /etc/nginx/conf.d/default.conf
COPY site/ /usr/share/nginx/html/
# Explicit non-root user (the base image already runs as uid 101; stated so scanners and reviewers see it)
USER 101
EXPOSE 8080
