package org.example.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    // BCrypt-encoded "password123"
    private static final String ENCODED_PASSWORD =
            new BCryptPasswordEncoder().encode("password123");

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.debug("Loading user details for username: '{}'", username);

        if (!"admin".equals(username)) {
            log.warn("User '{}' not found.", username);
            throw new UsernameNotFoundException("User not found: " + username);
        }

        log.info("User '{}' found — returning UserDetails.", username);
        return User.builder()
                .username("admin")
                .password(ENCODED_PASSWORD)
                .roles("USER")
                .build();
    }
}
