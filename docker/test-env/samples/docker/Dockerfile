# Use a slim Alpine image with a tiny embedded app for integration tests
FROM alpine:3.19
LABEL maintainer="apex-ci-library"
RUN apk add --no-cache bash
WORKDIR /app
COPY app.sh /app/app.sh
RUN chmod +x /app/app.sh
EXPOSE 8080
ENTRYPOINT ["/app/app.sh"]
