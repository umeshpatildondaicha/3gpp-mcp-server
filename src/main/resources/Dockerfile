# ============================================
# Multi-stage build for optimized image size
# ============================================
FROM container.visionwaves.com/visionwaves/alpine-fixed:3.20.3


# Set TimeZone
ENV TZ=UTC
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# ============================================
# Build arguments and environment variables
# ============================================
ARG APP_NAME
ARG APP_VERSION=5.0.0

ENV SERVICE_PATH=/opt/visionwaves/$APP_NAME \
    USER_NAME=visionwaves \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# ============================================
# Create directory structure
# ============================================
RUN mkdir -p $SERVICE_PATH

# ============================================
# Add application files
# ============================================
ADD ./$APP_NAME.tar $SERVICE_PATH/

# Remove unnecessary files to reduce image size
RUN rm -rf $SERVICE_PATH/ImportTemplateFiles \
           $SERVICE_PATH/db \
           $SERVICE_PATH/getSwagger.sh \
           $SERVICE_PATH/helm \
           $SERVICE_PATH/jwt.crt \
           $SERVICE_PATH/jwt.pem \
           $SERVICE_PATH/mock-responses \
           $SERVICE_PATH/realm.json \
           $SERVICE_PATH/spring \
           $SERVICE_PATH/application-local.properties


# ============================================
# Create non-root user for security
# ============================================
RUN adduser -D -u 1001 $USER_NAME && \
    chown -R $USER_NAME:$USER_NAME $SERVICE_PATH

# Set working directory
WORKDIR $SERVICE_PATH

# Switch to non-root user
USER $USER_NAME

# ============================================
# Health check for Kubernetes
# ============================================
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
    CMD curl -fsS "http://localhost:${MELODY_PORT:-3000}/rest/ping" || exit 1

# ============================================
# Expose application port
# ============================================
EXPOSE 3000

# ============================================
# Container startup command
# Logs go to STDOUT (no file tailing needed)
# Docker/K8s will capture and forward to ELK
# ============================================
CMD ["sh", "-c", "java $JAVA_OPTS -jar $SERVICE_PATH/3gpp-mcp-server-2.0.0.jar"]