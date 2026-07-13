FROM nginx:1.27-alpine
COPY dist /usr/share/nginx/html
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80 443 8510
