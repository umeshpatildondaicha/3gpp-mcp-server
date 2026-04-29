# ============================================
# Production image — VisionWaves pattern
# ============================================
FROM container.visionwaves.com/visionwaves/alpine-fixed:3.20.3

# Set TimeZone
ENV TZ=UTC
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# ============================================
# Build arguments and environment variables
# ============================================
ARG APP_NAME=3gpp-mcp
ARG APP_VERSION=2.0.0

ENV SSL_VAULT_PATH=/opt/visionwaves/sql_ssl \
    LICENSE_VAULT_PATH=/opt/visionwaves/license \
    SERVICE_PATH=/opt/visionwaves/${APP_NAME} \
    USER_NAME=visionwaves \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# ============================================
# Create directory structure
# ============================================
RUN mkdir -p $SERVICE_PATH $SSL_VAULT_PATH $LICENSE_VAULT_PATH

# Create directories for SkyWalking agent
RUN mkdir -p /opt/visionwaves/3gpp-mcp/agent

# Copy SkyWalking agent
COPY ./agent $SERVICE_PATH/agent/
COPY ./agent /opt/visionwaves/3gpp-mcp/agent/

# ============================================
# Add application files
# ============================================
ADD ./${APP_NAME}.tar $SERVICE_PATH/

# Remove unnecessary files
RUN rm -rf $SERVICE_PATH/ImportTemplateFiles \
           $SERVICE_PATH/db \
           $SERVICE_PATH/getSwagger.sh \
           $SERVICE_PATH/helm \
           $SERVICE_PATH/jwt.crt \
           $SERVICE_PATH/jwt.pem \
           $SERVICE_PATH/mock-responses \
           $SERVICE_PATH/realm.json \
           $SERVICE_PATH/spring \
           $SERVICE_PATH/Dockerfile \
           $SERVICE_PATH/application-local.properties

# Add melody post-hook script
ADD ./melodyposthook.sh /opt/
RUN chmod +x /opt/melodyposthook.sh

# ============================================
# Create non-root user for security
# ============================================
RUN adduser -D -u 1001 $USER_NAME && \
    chown -R $USER_NAME:$USER_NAME $SERVICE_PATH $SSL_VAULT_PATH $LICENSE_VAULT_PATH

RUN apk add --no-cache libc6-compat

# Set working directory
WORKDIR $SERVICE_PATH

# Switch to non-root user
USER $USER_NAME

# ============================================
# Health check for Kubernetes
# ============================================
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -f http://localhost:${MELODY_PORT:-8080}/health || exit 1

# ============================================
# Expose application port
# ============================================
EXPOSE 8080

# ============================================
# Container startup command
# ============================================
CMD ["sh", "run.sh", "start"]
