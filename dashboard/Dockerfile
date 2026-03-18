FROM node:22-alpine
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
EXPOSE 5173
# Run Vite host mode so it maps to Docker ports
CMD ["npm", "run", "dev", "--", "--host"]