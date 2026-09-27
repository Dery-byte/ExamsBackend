package com.exam.config.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Set;

/**
 * Writes the value only when the signed-in user is staff (Super Admin, HOD or lecturer);
 * everyone else gets null. Used for secrets such as a quiz's access password.
 */
public class StaffOnlySerializer extends JsonSerializer<Object> {

    private static final Set<String> STAFF = Set.of("SUPER_ADMIN", "ADMIN", "LECTURER");

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (isStaff()) serializers.defaultSerializeValue(value, gen);
        else gen.writeNull();
    }

    public static boolean isStaff() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(STAFF::contains);
    }
}
