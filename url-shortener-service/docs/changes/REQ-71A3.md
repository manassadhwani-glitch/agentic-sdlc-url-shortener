# REQ-71A3: Bug fix (security): the SSRF protection can be bypassed with alternative IP encodings. http://2130706433/ (decimal), http://0x7f000001/ (hex) and http://0177.0.0.1/ (octal) are accepted, but browsers and HTTP clients send them to 127.0.0.1. Reject non-canonical IP notation and add regression tests.

## Acceptance criteria and evidence

| AC | Criterion | Verified by |
|---|---|---|
| AC-1 | Non-http(s), internal, private-IP and credential-bearing URLs are rejected with 400 | UrlValidatorTest#rejectsUnsafeOrMalformedUrls, UrlApiIntegrationTest#validationErrorsAreProblemDetails |
| AC-2 | Hosts in non-canonical numeric IP notation (decimal, hex, octal, zero-padded) are rejected with 400, while canonical public IPs and hostnames still work | UrlValidatorEncodingTest#rejectsAlternativeIpEncodings, UrlValidatorEncodingTest#stillAcceptsCanonicalPublicIpsAndHostnames |

Test run: 102 tests, 0 failing (`mvn -q -B test`).

## Assumptions
- Single-node deployment; horizontal scaling is a documented follow-up

## Out of scope
- Multi-region deployment, user accounts and a web UI

## Design summary

See the design artifact for decisions and impacted modules.
