# MoonBlogger

## Project Overview

MoonBlogger is a multi-platform application composed of:

- Android client
- Django REST API
- PostgreSQL database
- Web frontend

The system follows a layered architecture.

Android and Web communicate with the Django REST API.
Neither client accesses PostgreSQL directly.

## Project State

Do not assume that a technology, dependency, component, endpoint, model, or convention exists unless it has been verified in the project.

When information is unavailable, distinguish between observed facts, assumptions, and recommendations.

## Architecture

Android
   │
   │ HTTPS / REST
   ▼
Django REST API
   │
   │ ORM
   ▼
PostgreSQL

Web
   │
   │ HTTPS / REST
   ▼
Django REST API

## General Rules
Do not bypass architectural layers.
Do not introduce dependencies without a clear reason.
Prefer simple, maintainable solutions over unnecessary abstractions.
Do not modify unrelated files while implementing a feature.
Changes to the API contract must be explicitly identified and coordinated with affected clients.
Do not make destructive database changes without explicitly explaining them.
Never hardcode secrets, API keys, passwords, or credentials.
Use environment variables for configuration and secrets.
Preserve existing project conventions unless there is a strong reason to change them.

## Development Philosophy

Before implementing a non-trivial feature:

1. Understand the existing architecture.
2. Inspect relevant code.
3. Identify dependencies and affected components.
4. Explain the proposed approach.
5. Implement the smallest reasonable change.
6. Run relevant tests or verification commands.
7. Report what changed and what was verified.

Do not rewrite working code merely for stylistic reasons.

## Communication

When making architectural decisions, explain:

Why the decision was made.
What alternatives were considered.
What tradeoffs exist.

When something is uncertain, explicitly state the uncertainty instead of inventing information.

## Agent Coordination

Planner coordinates project work and delegates tasks to specialized agents.

Planner:

- Transfers context to specialized agents (docs, decisions, contracts).
- Reviews results and runs verifications.
- Does not implement code that belongs to Backend, Database, Android or Frontend domains directly.

Architect:

- Reviews important technical decisions.
- Reviews changes that affect several domains before they are approved.

Specialized agents are responsible for their respective domains:

- Architect: system architecture and cross-component decisions.
- Android: Android client.
- Backend: Django and Django REST API.
- Frontend: web client.
- Database: PostgreSQL and data-layer design.

Implementation is delegated to the specialized agent of the corresponding domain. Agents must respect the boundaries of other components and communicate cross-component changes rather than modifying another component's domain without coordination.

## Testing

Changes should be verified at the narrowest useful level first.

Prefer:

Unit tests
Component/module tests
API tests
Integration tests
Full application tests

Do not claim that something works unless it has actually been verified.

## Git

Do not create commits unless explicitly requested.

Do not rewrite Git history.

Do not discard user changes.

## Project Documentation

Important architectural information belongs in:

docs/context.md
docs/architecture.md
docs/api.md
docs/database.md
docs/decisions.md

Keep documentation synchronized with significant architectural changes.