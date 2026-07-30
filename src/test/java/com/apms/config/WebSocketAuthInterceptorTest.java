package com.apms.config;

import com.apms.domain.project.repository.sql.ProjectMemberRepository;
import com.apms.security.JwtUtils;
import com.apms.security.UserDetailsImpl;
import com.apms.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

    @Mock
    private JwtUtils jwtUtils;
    @Mock
    private UserDetailsServiceImpl userDetailsService;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private MessageChannel messageChannel;

    @InjectMocks
    private WebSocketAuthInterceptor interceptor;

    private UserDetailsImpl userDetails;

    @BeforeEach
    void setUp() {
        userDetails = new UserDetailsImpl(
                1L, "user@example.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF")), true
        );
    }

    @Test
    void preSend_Connect_Success() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer valid-jwt");

        when(jwtUtils.validateJwtToken("valid-jwt")).thenReturn(true);
        when(jwtUtils.getUserNameFromJwtToken("valid-jwt")).thenReturn("user@example.com");
        when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(userDetails);

        accessor.setLeaveMutable(true);
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        Message<?> result = interceptor.preSend(message, messageChannel);

        assertNotNull(result);
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertNotNull(resultAccessor.getUser());
        assertTrue(resultAccessor.getUser() instanceof UsernamePasswordAuthenticationToken);
    }

    @Test
    void preSend_Connect_InvalidJwt_ThrowsException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer invalid-jwt");

        when(jwtUtils.validateJwtToken("invalid-jwt")).thenReturn(false);

        accessor.setLeaveMutable(true);
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message, messageChannel));
    }

    @Test
    void preSend_Subscribe_ValidMember_Success() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/projects/10");
        accessor.setUser(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

        when(projectMemberRepository.existsByProject_IdAndAccount_Id(10L, 1L)).thenReturn(true);

        accessor.setLeaveMutable(true);
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        Message<?> result = interceptor.preSend(message, messageChannel);

        assertNotNull(result);
    }

    @Test
    void preSend_Subscribe_NotMember_ThrowsException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/projects/10");
        accessor.setUser(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

        when(projectMemberRepository.existsByProject_IdAndAccount_Id(10L, 1L)).thenReturn(false);

        accessor.setLeaveMutable(true);
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message, messageChannel));
    }
}
