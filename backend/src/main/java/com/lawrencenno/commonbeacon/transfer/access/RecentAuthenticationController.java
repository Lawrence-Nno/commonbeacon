package com.lawrencenno.commonbeacon.transfer.access;

import com.lawrencenno.commonbeacon.shared.ApiFailure;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/account/data/reauthentication")
public class RecentAuthenticationController {
    public record Request(@NotBlank @Size(max=128) String password,@NotNull RecentAuthentication.Scope scope) {
        @Override public String toString(){return "PasswordConfirmation[redacted]";}
    }
    private final RecentAuthentication recent;
    public RecentAuthenticationController(RecentAuthentication recent){this.recent=recent;}
    @PostMapping public RecentAuthentication.Issued confirm(Authentication authentication,@Valid @RequestBody Request body,
            HttpServletRequest request,HttpServletResponse response) {
        try{return recent.issue(authentication,request.getSession(),request.getRemoteAddr(),body.password(),body.scope());}
        catch(ApiFailure e){if(e.status()==429)response.setHeader("Retry-After","900");throw e;}
    }
}
