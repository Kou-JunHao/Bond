package com.bond.transfer.controller;

import com.bond.common.dto.InvitationDTO;
import com.bond.common.dto.Result;
import com.bond.transfer.service.WorkspaceInvitationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/invite")
@RequiredArgsConstructor
public class InviteController {

    private final WorkspaceInvitationService invitationService;

    @GetMapping("/{token}")
    public Result<InvitationDTO> verifyLink(@PathVariable String token) {
        return Result.ok(invitationService.verifyInviteLink(token));
    }

    @PostMapping("/{token}/accept")
    public Result<Void> acceptLink(@RequestHeader("X-User-Id") Long userId,
                                   @PathVariable String token) {
        invitationService.acceptInviteLink(token, userId);
        return Result.ok();
    }
}
