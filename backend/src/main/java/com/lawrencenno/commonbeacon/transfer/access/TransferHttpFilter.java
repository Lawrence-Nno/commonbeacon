package com.lawrencenno.commonbeacon.transfer.access;

import com.lawrencenno.commonbeacon.shared.ApiProblems;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE+10)
public class TransferHttpFilter extends OncePerRequestFilter {
    private final ApiProblems problems;
    public TransferHttpFilter(ApiProblems problems){this.problems=problems;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getServletPath();
        if(!path.startsWith("/api/v1/account/data/") && !path.startsWith("/api/v1/admin/data/") && !path.startsWith("/api/v1/erasure/")){chain.doFilter(request,response);return;}
        response.setHeader("Cache-Control","no-store");response.setHeader("Pragma","no-cache");
        if(!request.getMethod().equals("POST")){chain.doFilter(request,response);return;}
        byte[] body=request.getInputStream().readNBytes(4097);
        if(body.length>4096){problems.write(response,413,"TRANSFER_LIMIT_EXCEEDED","The request is too large.");return;}
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream(){
                var input=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    public int read(){return input.read();}
                    public int read(byte[] b,int off,int len){return input.read(b,off,len);}
                    public boolean isFinished(){return input.available()==0;}
                    public boolean isReady(){return true;}
                    public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
                };
            }
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
        },response);
    }
}
