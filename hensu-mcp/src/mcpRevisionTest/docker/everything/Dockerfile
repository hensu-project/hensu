# The reference MCP server, installed from the package.json the test supplies.
# That file pins the server and, through npm overrides, the SDK that decides which
# protocol revision it speaks.
FROM node:22-alpine
WORKDIR /srv
COPY package.json .
RUN npm install --omit=dev --no-audit --no-fund
ENV PORT=3001
EXPOSE 3001
ENTRYPOINT ["node_modules/.bin/mcp-server-everything"]
