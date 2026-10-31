package com.stayos.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/** Forwarded headers affect login throttling only, and only from explicitly trusted proxies. */
@Component
public class ClientAddressResolver {
    private final List<IpAddressMatcher> trusted;
    public ClientAddressResolver(@Value("${stayos.auth.trusted-proxies:}") String configuration) {
        trusted=Arrays.stream(configuration.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(s->{
            if(!literal(s.split("/",-1)[0]))throw new IllegalArgumentException("Trusted proxies must be literal IP addresses or CIDRs");
            return new IpAddressMatcher(s);
        }).toList();
    }
    private static boolean literal(String value) {
        if(value==null || value.length()>45 || !value.matches("[0-9A-Fa-f:.]+"))return false;
        try {
            if(value.contains(":"))return InetAddress.getByName(value).getAddress().length==16;
            var parts=value.split("\\.",-1);if(parts.length!=4)return false;
            for(String part:parts)if(!part.matches("[0-9]{1,3}")||Integer.parseInt(part)>255)return false;
            return true;
        } catch(Exception ignored){return false;}
    }
    private boolean trusted(String ip){return trusted.stream().anyMatch(m->m.matches(ip));}
    public String resolve(HttpServletRequest request) {
        String peer=request.getRemoteAddr();if(!trusted(peer))return peer;
        String header=request.getHeader("X-Forwarded-For");if(header==null||header.length()>1024)return peer;
        var chain=header.split(",",-1);if(chain.length>20)return peer;
        for(String part:chain)if(!literal(part.trim()))return peer;
        String current=peer;
        for(int i=chain.length-1;i>=0 && trusted(current);i--)current=chain[i].trim();
        return current;
    }
}
