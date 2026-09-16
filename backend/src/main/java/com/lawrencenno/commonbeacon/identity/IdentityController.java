package com.lawrencenno.commonbeacon.identity;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class IdentityController {
    private final IdentityService identity;
    public IdentityController(IdentityService identity) { this.identity = identity; }
    public record CsrfResponse(String headerName, String token) {}
    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getHeaderName(), token.getToken()));
    }
    @PostMapping("/register")
    public ResponseEntity<UserSummary> register(@Valid @RequestBody RegistrationRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(identity.register(request));
    }
    @GetMapping("/me")
    public ResponseEntity<UserSummary> me(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(identity.current(authentication));
    }
}
