FROM node:20-alpine
WORKDIR /app
COPY panel/package.json ./
RUN npm install --omit=dev
COPY panel/ ./
ENV PORT=8787
ENV DATA_DIR=/data
ENV DIST_DIR=/app/dist
EXPOSE 8787
VOLUME ["/data"]
CMD ["node", "server.js"]
