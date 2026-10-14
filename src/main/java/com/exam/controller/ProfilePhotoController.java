package com.exam.controller;

import com.exam.exception.ErrorMessage;
import com.exam.model.User;
import com.exam.repository.UserRepository;
import com.exam.service.QuestionImageService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * Every signed-in user's own profile photo (Super Admin, HODs, lecturers, students). Stored like
 * question images: checked to really be a PNG or JPEG, cropped to a centred square, scaled down and
 * compressed to WebP in the database, and served from the same public UUID path.
 */
@RestController
@RequestMapping("/api/v1/auth/profile/photo")
public class ProfilePhotoController {

    @Autowired private CurrentUserService currentUserService;
    @Autowired private UserRepository users;
    @Autowired private QuestionImageService images;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        User me = me();
        if (me == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorMessage("Please sign in."));
        String path = images.storeProfilePhoto(file);
        String old = me.getProfilePic();
        me.setProfilePic(path);
        users.save(me);
        images.deleteByPath(old);   // only removes photos stored here, never a Google picture URL
        return ResponseEntity.ok(body(path));
    }

    @DeleteMapping
    @Transactional
    public ResponseEntity<?> remove() {
        User me = me();
        if (me == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorMessage("Please sign in."));
        String old = me.getProfilePic();
        me.setProfilePic(null);
        users.save(me);
        images.deleteByPath(old);
        return ResponseEntity.ok(body(null));
    }

    /** The signed-in user, freshly loaded so the save doesn't overwrite newer changes. */
    private User me() {
        return currentUserService.current().flatMap(u -> users.findById(u.getId())).orElse(null);
    }

    private static Map<String, Object> body(String path) {
        Map<String, Object> m = new HashMap<>();
        m.put("profilePicture", path);
        return m;
    }
}
