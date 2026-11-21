# Changelog

## REQ-71A3
- Bug fix (security): the SSRF protection can be bypassed with alternative IP encodings. http://2130706433/ (decimal), http://0x7f000001/ (hex) and http://0177.0.0.1/ (octal) are accepted, but browsers and HTTP clients send them to 127.0.0.1. Reject non-canonical IP notation and add regression tests.
- Features: url-safety, ip-encoding-fix

