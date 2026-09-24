package com.souldealers.crowdtracebackend.modules.identity;

import com.souldealers.crowdtracebackend.shared.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@AllArgsConstructor
@Slf4j
public class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final CustomUserDetailService userDetailsService;
    private static final String HEADER = "Authorization";
    private static final String BEARER_SUB_STRING = "Bearer";
    private static final int TOKEN_SUB_STRING_INDEX = 7;
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        final String authenticationHeader = request.getHeader(HEADER);
        final String token;
        final String userEmail;

        if (authenticationHeader == null || !authenticationHeader.startsWith(BEARER_SUB_STRING)){
            filterChain.doFilter(request,response);
            return;
        }

        try {
            token = authenticationHeader.substring(TOKEN_SUB_STRING_INDEX);
            userEmail = jwtService.extractEmail(token);

            if(userEmail !=null && SecurityContextHolder.getContext().getAuthentication() == null){
                UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

                if(userDetails.isEnabled() && jwtService.isTokenValid(token,userDetails)){
                    UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authenticationToken);
                }
            }
        } catch (Exception e){
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
