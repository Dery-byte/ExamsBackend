package com.exam.service.comms;

import com.exam.model.User;
import com.exam.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Resolves the signed-in user from the security context. */
@Service
public class CurrentUserService {

    @Autowired
    private UserRepository userRepository;

    public Optional<User> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        if (auth.getPrincipal() instanceof User u && u.getId() != null) return Optional.of(u);
        String name = auth.getName();
        if (name == null || "anonymousUser".equals(name)) return Optional.empty();
        return userRepository.findByUsername(name);
    }

    public static String displayName(User u) {
        if (u == null) return null;
        String full = ((u.getFirstname() != null ? u.getFirstname() : "") + " "
                + (u.getLastname() != null ? u.getLastname() : "")).trim();
        return full.isEmpty() ? u.getUsername() : full;
    }
}
