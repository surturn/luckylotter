package com.lucklotter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Admin changes their own password. */
public record ChangePasswordRequest(

    @NotBlank(message = "current password is required")
    @Size(max = 200)
    String currentPassword,

    @NotBlank(message = "new password is required")
    @Size(min = 12, max = 200, message = "new password must be at least 12 characters")
    String newPassword
) {
}
