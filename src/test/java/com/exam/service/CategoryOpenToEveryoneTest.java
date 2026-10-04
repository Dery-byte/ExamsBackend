package com.exam.service;

import com.exam.model.exam.Category;
import com.exam.model.exam.Program;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Who may open a course to everyone, and on which courses. */
class CategoryOpenToEveryoneTest {

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "someone", null, List.of(new SimpleGrantedAuthority(role))));
    }

    private static Category course(boolean global, Boolean open) {
        Category c = new Category();
        if (!global) c.setPrograms(Set.of(Program.builder().id(1L).name("CS").code("CS").durationYears(4).build()));
        c.setOpenToEveryone(open);
        return c;
    }

    @Test
    void superAdminOpensAGlobalCourse() {
        signInAs("SUPER_ADMIN");
        Category c = course(true, null);
        CategoryService.applyOpenToEveryone(c, true);
        assertThat(c.opensToEveryone()).isTrue();
    }

    @Test
    void onlyAGlobalCourseCanBeOpened() {
        signInAs("SUPER_ADMIN");
        assertThatThrownBy(() -> CategoryService.applyOpenToEveryone(course(false, null), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void othersCannotChangeIt() {
        signInAs("ADMIN");
        assertThatThrownBy(() -> CategoryService.applyOpenToEveryone(course(true, null), true))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> CategoryService.applyOpenToEveryone(course(true, true), false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void othersCanStillEditAnOpenCourseWithoutChangingIt() {
        signInAs("ADMIN");
        Category open = course(true, true);
        CategoryService.applyOpenToEveryone(open, true);   // the edit form sends the current value back
        CategoryService.applyOpenToEveryone(open, null);   // or leaves it out
        assertThat(open.opensToEveryone()).isTrue();
    }

    @Test
    void addingProgramsEndsIt() {
        signInAs("SUPER_ADMIN");
        Category c = course(false, true);   // was open, then programs were assigned
        CategoryService.applyOpenToEveryone(c, null);
        assertThat(c.isOpenToEveryone()).isFalse();
    }
}
