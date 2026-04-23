FROM node:22-slim

# Non-root user for security
RUN groupadd -r mcpuser && useradd -r -g mcpuser -m mcpuser

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY index.js ./

# Cache directory owned by app user
RUN mkdir -p /home/mcpuser/.3gpp-kb && chown -R mcpuser:mcpuser /home/mcpuser

USER mcpuser

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=3 \
  CMD node -e "fetch('http://localhost:3000/ready').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "index.js"]
