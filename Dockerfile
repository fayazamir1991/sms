FROM node:24-bookworm-slim
WORKDIR /app/server
ENV NODE_ENV=production PORT=8080 HOST=0.0.0.0 DATA_DIR=/data
# No npm install: the HTTP server, database and cryptography use Node built-ins.
COPY server/package.json ./package.json
COPY server/src ./src
COPY server/public ./public
COPY server/scripts/build-seed.mjs ./scripts/build-seed.mjs
COPY server/scripts/fetch-font.mjs ./scripts/fetch-font.mjs
COPY server/data ./data
COPY server/migrations/0001_schema.sql ./migrations/0001_schema.sql
COPY server/migrations/0003_catalog_imports.sql ./migrations/0003_catalog_imports.sql
COPY server/migrations/0004_web_frontier.sql ./migrations/0004_web_frontier.sql
COPY server/migrations/0005_source_metadata.sql ./migrations/0005_source_metadata.sql
COPY server/migrations/0007_catalog_offers.sql ./migrations/0007_catalog_offers.sql
COPY server/migrations/0009_catalog_search.sql ./migrations/0009_catalog_search.sql
RUN node scripts/fetch-font.mjs && node scripts/build-seed.mjs && mkdir -p /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
 CMD node -e "fetch('http://127.0.0.1:'+process.env.PORT+'/healthz').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["node", "src/runtime.mjs"]
