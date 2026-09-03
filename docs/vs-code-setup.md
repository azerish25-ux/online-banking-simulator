# VS Code Setup

1. Open this folder: **File → Open Folder → `project 1`**
2. Install recommended extensions when prompted (see `.vscode/extensions.json`):
   - ESLint, Prettier, Tailwind (later), Java Extension Pack, Spring Boot Extension Pack, Docker
3. Frontend: open terminal → `Set-Location frontend; npm install; npm run dev`
4. Backend: needs JDK 17 (you have it ✅). Maven: use wrapper `.\mvnw.cmd` (no global install needed).
   - First run downloads Maven automatically.
5. Useful commands:
   - Frontend lint: `npm run lint`
   - Backend run: `.\mvnw.cmd spring-boot:run`
   - Backend test: `.\mvnw.cmd test`
6. Debugging: `.vscode/launch.json` has "Next.js: dev" and "Spring Boot: run" configs.
