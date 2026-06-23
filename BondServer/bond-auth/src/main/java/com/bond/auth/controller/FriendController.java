package com.bond.auth.controller;

import com.bond.auth.service.FriendService;
import com.bond.common.dto.FriendDTO;
import com.bond.common.dto.FriendRequestDTO;
import com.bond.common.dto.Result;
import com.bond.common.dto.UserSearchDTO;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;

    @PostMapping("/request")
    public Result<Void> sendRequest(@RequestHeader("X-User-Id") Long userId,
                                    @RequestBody SendRequest req) {
        friendService.sendRequest(userId, req.getToUserId(), req.getMessage());
        return Result.ok();
    }

    @GetMapping("/requests")
    public Result<List<FriendRequestDTO>> getRequests(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(friendService.getPendingRequests(userId));
    }

    @PostMapping("/requests/{id}/accept")
    public Result<Void> accept(@RequestHeader("X-User-Id") Long userId,
                               @PathVariable Long id) {
        friendService.acceptRequest(userId, id);
        return Result.ok();
    }

    @PostMapping("/requests/{id}/reject")
    public Result<Void> reject(@RequestHeader("X-User-Id") Long userId,
                               @PathVariable Long id) {
        friendService.rejectRequest(userId, id);
        return Result.ok();
    }

    @GetMapping
    public Result<List<FriendDTO>> listFriends(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(friendService.getFriends(userId));
    }

    @DeleteMapping("/{friendUserId}")
    public Result<Void> remove(@RequestHeader("X-User-Id") Long userId,
                               @PathVariable Long friendUserId) {
        friendService.removeFriend(userId, friendUserId);
        return Result.ok();
    }

    @GetMapping("/search")
    public Result<List<UserSearchDTO>> search(@RequestParam String q) {
        return Result.ok(friendService.searchUsers(q));
    }

    @Data
    static class SendRequest {
        private Long toUserId;
        private String message;
    }
}
