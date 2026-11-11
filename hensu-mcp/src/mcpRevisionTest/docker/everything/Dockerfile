# The reference MCP server, installed from the package.json and package-lock.json
# of one revision directory beside this file. package.json pins the server and,
# through npm overrides, the SDK that decides which protocol revision it speaks;
# the lockfile pins every package below them, so a rebuild installs the same tree.
FROM node:24-alpine
WORKDIR /srv
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
ENV PORT=3001
EXPOSE 3001
ENTRYPOINT ["node_modules/.bin/mcp-server-everything"]
