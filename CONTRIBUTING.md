# Contributing to OptiQueue

Thank you for your interest in contributing to OptiQueue! We welcome contributions, bug reports, feature suggestions, and documentation enhancements.

---

## 1. Code of Conduct

All contributors and maintainers are expected to abide by our [Code of Conduct](CODE_OF_CONDUCT.md). Please ensure a respectful and collaborative atmosphere in all interactions.

---

## 2. Getting Started

### 2.1 Prerequisites
- **Java 17 or Java 21** JDK installed
- **Docker & Docker Compose** (for local PostgreSQL and Redis dependencies)
- **Maven 3.8+** (or use the provided `./mvnw` wrapper)

### 2.2 Local Development Setup
1. Clone the repository:
   ```bash
   git clone https://github.com/Kaushtubha/optiqueue.git
   cd optiqueue
   ```
2. Start the backing infrastructure services:
   ```bash
   docker-compose up -d postgres redis
   ```
3. Build the application and run unit tests:
   ```bash
   ./mvnw clean test
   ```
4. Start the application locally:
   ```bash
   ./mvnw spring-boot:run
   ```

---

## 3. Commit Message Guidelines

We enforce the [Conventional Commits](https://www.conventionalcommits.org/) standard. Commit messages must be structured as follows:

```
<type>(<scope>): <subject>
```

### Allowed Types
- **`feat`**: A new feature or capability
- **`fix`**: A bug fix
- **`docs`**: Documentation-only changes
- **`refactor`**: Code change that neither fixes a bug nor adds a feature
- **`test`**: Adding missing tests or correcting existing tests
- **`chore`**: Maintenance, build tool, or dependency updates

### Examples
- `feat(scheduler): implement dynamic exponential backoff jitter`
- `fix(failover): resolve lease renewal race condition in Redis`
- `docs(api): update OpenAPI request payload examples`

---

## 4. Coding Standards

- **Formatting**: Code follows standard Java conventions with 4-space indentation.
- **Documentation**: All public classes, interfaces, and public methods must include descriptive JavaDoc.
- **Test Coverage**: All new business logic must include unit and integration tests.
- **Thread Safety**: Any stateful components shared between worker threads must be explicitly thread-safe.

---

## 5. Submitting a Pull Request

1. Create a feature branch from `main`:
   ```bash
   git checkout -b feature/my-feature-name
   ```
2. Implement your changes with accompanying tests.
3. Verify that the build and tests pass cleanly:
   ```bash
   ./mvnw clean verify
   ```
4. Push your branch and open a Pull Request against `main`. Provide a clear description of the problem solved, design decisions made, and test validation steps.
