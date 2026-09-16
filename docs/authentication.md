# Stage 4: registration and secure sessions

## Try it locally

Start PostgreSQL, the backend, and Vite using [frontend setup](frontend-setup.md).
Open http://127.0.0.1:5173/register, create a fictional member, then sign in.
Reload to verify the session survives browser navigation; use **Sign out** to invalidate it.

Registration does not sign the user in automatically. There are no seeded administrator
accounts yet; board administration and development demo users belong to Stage 5.

## API contract

All paths below are relative to /api/v1/auth. Browser requests use the same origin
through the Vite proxy and send the JSESSIONID cookie automatically.

- GET /csrf: public; returns `{ "headerName": "X-CSRF-TOKEN", "token": "..." }`.
  Establishes the session needed for CSRF protection. Responses must not be cached.
- POST /register: JSON containing email, displayName, and password; returns 201.
  Emails are trimmed and lowercased with Locale.ROOT before validation/persistence.
  Display names are trimmed, 1–80 characters; emails are required, valid, at most 254
  characters; passwords are 12–128 characters and are never trimmed.
  Unknown JSON properties (including role) are ignored. Public registration always
  assigns MEMBER. PostgreSQL's unique constraint handles concurrent duplicates.
- POST /login: application/x-www-form-urlencoded containing email and password.
  Returns 200 with the current user or generic 401 for invalid credentials.
  Spring Security handles authentication, session storage, and session-ID rotation.
- GET /me: returns the current user, or 401 when anonymous.
- POST /logout: requires CSRF; invalidates the session, clears authentication,
  deletes the session cookie, and returns 204.

Successful registration/login/me responses contain only id, displayName, and role.
No email or password hash is returned. Passwords use Spring Security's salted PBKDF2
encoder through DelegatingPasswordEncoder; stored hashes include the encoder ID.

All POST requests, including registration and login, need the header/token from /csrf.
The frontend fetches a fresh token before each mutation so login/logout token renewal
is respected. Mutations are never automatically retried: a lost response does not
prove the server did not apply the request.

Validation errors return application/problem+json with status, detail, code,
requestId, and fieldErrors. Duplicate email returns 409; missing/invalid CSRF and
denied access return 403; bounded login attempts return 429 with Retry-After.
Security-filter failures also use ProblemDetail. No submitted passwords or SQL
details are included in these bodies.

## Session and authorization behavior

- Cookies are HttpOnly and SameSite=Lax. The session idle timeout is 30 minutes.
- Secure cookies are the default. Local HTTP explicitly uses SPRING_PROFILES_ACTIVE=local;
  scripts/use-local-database.ps1 selects that profile for the current terminal.
  HTTPS deployment uses SPRING_PROFILES_ACTIVE=prod, which enforces Secure.
- Sessions are in application memory; backend restart signs users out.
- /api/v1/admin/** requires ADMINISTRATOR; /api/v1/moderation/** requires MODERATOR
  or ADMINISTRATOR. These reserve authorization rules; product endpoints arrive
  in their respective stages.
- IdentityService.requireOwner compares the authenticated user ID with the owner.
  Administrators/moderators have no implicit ownership bypass.
- The browser checks /me on load, window focus, and every minute while visible.
  Logout, account switch, and detected expiration cancel requests and clear the
  TanStack Query cache. A stale startup response cannot overwrite a newer login.
  Network failure reports uncertainty without pretending the session is gone.
- Form errors preserve email/display name and clear the password. Controls prevent
  repeated submission while pending.

## Login throttling and current limits

The single-instance limiter permits ten login attempts per remote address per minute,
including successes. It retains at most 10,000 address windows and returns 429 when
capacity is reached. It uses the socket address, not untrusted forwarded headers.
Through the local Vite proxy, users share the proxy address and therefore its limit.

This is a local learning implementation. Multi-instance sessions, distributed
throttling, trusted deployment proxy configuration, email verification, password
reset, and MFA are not implemented. These require separate design before deployment.

## Verification

Run `scripts/verify.ps1` as described in the setup guide. Backend tests use disposable
PostgreSQL containers. IdentityIT exercises real HTTP cookie sessions rather than
mocked principals: normalization, member-only registration, password hashing,
concurrent duplicate registration, session fixation protection, renewed CSRF,
logout invalidation, denied role/ownership access, and HTTP throttling.

Frontend tests cover form errors, registration success, cache clearing, stale
startup responses, CSRF renewal, credential encoding, and no automatic mutation retry.

With the backend running, `npm run test:smoke` in frontend also exercises registration,
sign-in, reload, logout, invalid credentials, and externally invalidated sessions
in Chrome. The auth smoke test creates one unique fictional smoke-UUID@example.test
account in the configured development database. Its address is attached to the test
result; the test does not delete existing data. Use a disposable database for
repeated smoke runs. Full isolated community workflows arrive in Stage 10.

## Java learning checkpoint

**Authentication** establishes who made a request. Spring Security filters process
cookies, CSRF, and credentials before the controller runs. A successful login stores
authentication in the session and changes the session ID to prevent fixation.

**Authorization** determines what that authenticated user may do. URL rules check
roles; the identity service supports owner checks using the authenticated user's
ID. The frontend may hide controls, but backend checks enforce permissions.

**DTOs** define validated inputs and safe response fields. The JPA entity stores
the password hash, while UserSummary intentionally omits it. **Transactions** keep
registration persistence atomic; a database uniqueness constraint remains decisive
when two requests arrive at once.

## Verified results — 2026-09-16

- Combined scripts/verify.ps1: exit 0.
- Backend: 2 unit tests and 17 integration tests; no failures, errors, or skips.
  This includes a real HTTP production-profile Secure-cookie test.
- Frontend: 21 tests, ESLint, TypeScript checks, and production build passed.
- Chrome: all 4 smoke tests passed against the rebuilt backend with the local profile.
- Desktop registration and mobile login screenshots inspected; mobile width check passed.
- Stage 4 changes remain uncommitted; remote CI has not run for this stage.
