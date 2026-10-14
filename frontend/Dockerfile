FROM node:22-alpine AS build
WORKDIR /app
RUN corepack enable && corepack prepare pnpm@10 --activate
COPY package.json ./
RUN pnpm install --no-frozen-lockfile
COPY . .
ARG NEXT_PUBLIC_FIREBASE_API_KEY=demo-key
ARG NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=demo-aquatrace-local.firebaseapp.com
ARG NEXT_PUBLIC_FIREBASE_PROJECT_ID=demo-aquatrace-local
ARG NEXT_PUBLIC_FIREBASE_APP_ID=1:000000000000:web:local-aquatrace
ARG NEXT_PUBLIC_FIREBASE_EMULATOR=true
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8081
ENV NEXT_PUBLIC_FIREBASE_API_KEY=$NEXT_PUBLIC_FIREBASE_API_KEY \
    NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=$NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN \
    NEXT_PUBLIC_FIREBASE_PROJECT_ID=$NEXT_PUBLIC_FIREBASE_PROJECT_ID \
    NEXT_PUBLIC_FIREBASE_APP_ID=$NEXT_PUBLIC_FIREBASE_APP_ID \
    NEXT_PUBLIC_FIREBASE_EMULATOR=$NEXT_PUBLIC_FIREBASE_EMULATOR \
    NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
RUN pnpm build

FROM node:22-alpine AS run
WORKDIR /app
ENV NODE_ENV=production PORT=3000
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
EXPOSE 3000
CMD ["node", "server.js"]
