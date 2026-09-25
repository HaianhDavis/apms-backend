package com.apms.config;

import com.apms.domain.project.repository.sql.ProjectMemberRepository;
import com.apms.security.JwtUtils;
import com.apms.security.UserDetailsImpl;
import com.apms.security.UserDetailsServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtUtils jwtUtils;
    private final UserDetailsServiceImpl userDetailsService;
    private final ProjectMemberRepository projectMemberRepository;

    private static final Pattern DESTINATION_PATTERN = Pattern.compile("^/(?:topic|app)/projects/(\\d+).*");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
                String jwt = authHeader.substring(7);
                if (jwtUtils.validateJwtToken(jwt)) {
                    String username = jwtUtils.getUserNameFromJwtToken(jwt);
                    UserDetailsImpl userDetails = (UserDetailsImpl) userDetailsService.loadUserByUsername(username);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    accessor.setUser(authentication);
                } else {
                    throw new AccessDeniedException("Invalid JWT token");
                }
            } else {
                throw new AccessDeniedException("Missing or invalid Authorization header");
            }
        }

        if (accessor != null && (StompCommand.SUBSCRIBE.equals(accessor.getCommand()) || StompCommand.SEND.equals(accessor.getCommand()))) {
            if (accessor.getUser() == null || !(accessor.getUser() instanceof UsernamePasswordAuthenticationToken)) {
                 throw new AccessDeniedException("Not authenticated");
            }

            UserDetailsImpl userDetails = (UserDetailsImpl) ((UsernamePasswordAuthenticationToken) accessor.getUser()).getPrincipal();
            String destination = accessor.getDestination();

            if (destination != null) {
                Matcher matcher = DESTINATION_PATTERN.matcher(destination);
                if (matcher.matches()) {
                    Long projectId = Long.parseLong(matcher.group(1));
                    boolean isSystemAdmin = userDetails.getAuthorities() != null && userDetails.getAuthorities().stream()
                            .anyMatch(a -> "ROLE_SYSTEM_ADMIN".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
                    if (!isSystemAdmin && !projectMemberRepository.existsByProject_IdAndAccount_Id(projectId, userDetails.getId())) {
                        throw new AccessDeniedException("User is not a member of project " + projectId);
                    }
                }
            }
        }

        return message;
    }
}
