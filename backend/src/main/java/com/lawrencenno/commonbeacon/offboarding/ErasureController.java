package com.lawrencenno.commonbeacon.offboarding;

import com.lawrencenno.commonbeacon.transfer.access.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/erasure")
public class ErasureController {
    public record Preview(@NotNull ErasureService.Scope scope) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("UNKNOWN_FIELD");}
    }
    public record Confirmation(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String digest,
        @NotBlank @Size(max=100) String phrase,@NotNull @Size(max=4) Set<String> acknowledgements,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String receiptToken,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String recentAuthGrant) {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("UNKNOWN_FIELD");}
        @Override public String toString(){return "ErasureConfirmation[redacted]";}
    }
    private final ErasureService service;private final TransferAccess access;private final RecentAuthentication recent;
    public ErasureController(ErasureService service,TransferAccess access,RecentAuthentication recent){this.service=service;this.access=access;this.recent=recent;}
    @PostMapping("/previews")
    public ResponseEntity<JsonNode> preview(Authentication auth,@Valid @RequestBody Preview body) {
        var actor=access.current(auth,body.scope()==ErasureService.Scope.COMPANY);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(service.preview(actor.id(),body.scope()));
    }
    @PostMapping("/{id}/confirm")
    public ResponseEntity<JsonNode> confirm(Authentication auth,HttpServletRequest request,@PathVariable UUID id,@Valid @RequestBody Confirmation body) {
        var actor=access.current(auth,false);
        // The persisted preview selects scope; a grant for one scope cannot authorize the other.
        service.confirm(actor.id(),id,body.digest(),body.phrase(),body.acknowledgements(),body.receiptToken(),current->{
            var scope=body.phrase().equals("DELETE MY ACCOUNT")?RecentAuthentication.Scope.ACCOUNT_ERASURE:RecentAuthentication.Scope.COMPANY_ERASURE;
            recent.consume(current,request.getSession(),scope,body.recentAuthGrant());
        });
        if(body.phrase().equals("DELETE MY ACCOUNT")) {
            var session=request.getSession(false);if(session!=null)session.invalidate();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        return ResponseEntity.accepted().cacheControl(org.springframework.http.CacheControl.noStore()).body(service.status(id,body.receiptToken()));
    }
    @GetMapping("/receipts/{id}")
    public ResponseEntity<JsonNode> receipt(@PathVariable UUID id,@RequestHeader("X-Erasure-Receipt") String token) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(service.status(id,token));
    }
}
