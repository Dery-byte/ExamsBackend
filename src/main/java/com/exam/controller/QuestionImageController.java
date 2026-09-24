package com.exam.controller;

import com.exam.service.QuestionImageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/auth")
public class QuestionImageController {

    @Autowired
    private QuestionImageService imageService;

    /** Admin / Lecturer: upload a PNG/JPG/JPEG; it is converted to WebP. */
    @PostMapping(value = "/question-images/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> upload(@RequestParam("file") MultipartFile file,
                                                      Authentication authentication) {
        boolean allowed = authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ADMIN") || a.equals("LECTURER") || a.equals("SUPER_ADMIN"));
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins and lecturers can upload images");
        }
        return ResponseEntity.ok(Map.of("image", imageService.storeAsWebp(file)));
    }

    /** Public (an &lt;img&gt; tag cannot send the JWT); ids are unguessable UUIDs. */
    @GetMapping("/question-images/{id}.webp")
    public ResponseEntity<byte[]> get(@PathVariable String id) {
        byte[] data = imageService.load(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/webp"))
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                .body(data);
    }
}
