FROM node:22-alpine
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
EXPOSE 5173

# Launch Vite development server bound to all network interfaces for container port mapping
CMD ["npm", "run", "dev", "--", "--host"]