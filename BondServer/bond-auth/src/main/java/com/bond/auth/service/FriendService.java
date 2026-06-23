package com.bond.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.auth.mapper.FriendshipMapper;
import com.bond.auth.mapper.UserMapper;
import com.bond.common.dto.FriendDTO;
import com.bond.common.dto.FriendRequestDTO;
import com.bond.common.dto.UserSearchDTO;
import com.bond.common.entity.Friendship;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.SnowflakeId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FriendService {

    private final FriendshipMapper friendshipMapper;
    private final UserMapper userMapper;
    private final ConfigService configService;

    private static final int STATUS_PENDING = 0;
    private static final int STATUS_ACCEPTED = 1;
    private static final int STATUS_REJECTED = 2;
    private static final int STATUS_BLOCKED = 3;

    public void sendRequest(Long fromUserId, Long toUserId, String message) {
        if (fromUserId.equals(toUserId)) {
            throw new BusinessException(400, "不能添加自己为好友");
        }
        User target = userMapper.selectById(toUserId);
        if (target == null) throw new BusinessException(404, "用户不存在");

        // Check if friendship already exists (either direction)
        Friendship existing = findFriendship(fromUserId, toUserId);
        if (existing != null) {
            if (existing.getStatus() == STATUS_ACCEPTED) {
                throw new BusinessException(400, "已经是好友");
            }
            if (existing.getStatus() == STATUS_PENDING) {
                throw new BusinessException(400, "已发送过好友请求");
            }
            if (existing.getStatus() == STATUS_BLOCKED) {
                throw new BusinessException(400, "无法添加该用户");
            }
            // If rejected, allow re-request by updating
            existing.setStatus(STATUS_PENDING);
            existing.setUpdatedAt(LocalDateTime.now());
            friendshipMapper.updateById(existing);
            return;
        }

        // Check friend count
        int maxFriends = configService.getInt("friend.max_friends", 500);
        long count = friendshipMapper.selectCount(
                new LambdaQueryWrapper<Friendship>()
                        .eq(Friendship::getRequesterId, fromUserId)
                        .eq(Friendship::getStatus, STATUS_ACCEPTED));
        if (count >= maxFriends) {
            throw new BusinessException(400, "好友数已达上限");
        }

        Friendship f = new Friendship();
        f.setId(SnowflakeId.nextId());
        f.setRequesterId(fromUserId);
        f.setAddresseeId(toUserId);
        f.setStatus(STATUS_PENDING);
        friendshipMapper.insert(f);
    }

    public void acceptRequest(Long userId, Long friendshipId) {
        Friendship f = friendshipMapper.selectById(friendshipId);
        if (f == null || !f.getAddresseeId().equals(userId)) {
            throw new BusinessException(404, "好友请求不存在");
        }
        if (f.getStatus() != STATUS_PENDING) {
            throw new BusinessException(400, "该请求已处理");
        }
        f.setStatus(STATUS_ACCEPTED);
        f.setUpdatedAt(LocalDateTime.now());
        friendshipMapper.updateById(f);
    }

    public void rejectRequest(Long userId, Long friendshipId) {
        Friendship f = friendshipMapper.selectById(friendshipId);
        if (f == null || !f.getAddresseeId().equals(userId)) {
            throw new BusinessException(404, "好友请求不存在");
        }
        f.setStatus(STATUS_REJECTED);
        f.setUpdatedAt(LocalDateTime.now());
        friendshipMapper.updateById(f);
    }

    public List<FriendRequestDTO> getPendingRequests(Long userId) {
        List<Friendship> list = friendshipMapper.selectList(
                new LambdaQueryWrapper<Friendship>()
                        .eq(Friendship::getAddresseeId, userId)
                        .eq(Friendship::getStatus, STATUS_PENDING)
                        .orderByDesc(Friendship::getCreatedAt));

        return list.stream().map(f -> {
            User sender = userMapper.selectById(f.getRequesterId());
            FriendRequestDTO dto = new FriendRequestDTO();
            dto.setId(f.getId());
            dto.setFromUserId(f.getRequesterId());
            dto.setFromUsername(sender != null ? sender.getUsername() : "");
            dto.setFromNickname(sender != null ? sender.getNickname() : "");
            dto.setStatus(f.getStatus());
            dto.setCreatedAt(f.getCreatedAt() != null
                    ? f.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
            return dto;
        }).collect(Collectors.toList());
    }

    public List<FriendDTO> getFriends(Long userId) {
        // Get all accepted friendships (both directions)
        List<Friendship> asRequester = friendshipMapper.selectList(
                new LambdaQueryWrapper<Friendship>()
                        .eq(Friendship::getRequesterId, userId)
                        .eq(Friendship::getStatus, STATUS_ACCEPTED));
        List<Friendship> asAddressee = friendshipMapper.selectList(
                new LambdaQueryWrapper<Friendship>()
                        .eq(Friendship::getAddresseeId, userId)
                        .eq(Friendship::getStatus, STATUS_ACCEPTED));

        List<FriendDTO> result = new ArrayList<>();
        for (Friendship f : asRequester) {
            User u = userMapper.selectById(f.getAddresseeId());
            if (u != null) result.add(toFriendDTO(u, f));
        }
        for (Friendship f : asAddressee) {
            User u = userMapper.selectById(f.getRequesterId());
            if (u != null) result.add(toFriendDTO(u, f));
        }
        return result;
    }

    public void removeFriend(Long userId, Long friendUserId) {
        Friendship f = findFriendship(userId, friendUserId);
        if (f == null || f.getStatus() != STATUS_ACCEPTED) {
            throw new BusinessException(404, "好友关系不存在");
        }
        friendshipMapper.deleteById(f.getId());
    }

    public boolean areFriends(Long userId1, Long userId2) {
        Friendship f = findFriendship(userId1, userId2);
        return f != null && f.getStatus() == STATUS_ACCEPTED;
    }

    public List<UserSearchDTO> searchUsers(String keyword) {
        return userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .and(w -> w
                                .like(User::getUsername, keyword)
                                .or()
                                .like(User::getNickname, keyword))
                        .last("LIMIT 20"))
                .stream().map(u -> {
                    UserSearchDTO dto = new UserSearchDTO();
                    dto.setId(u.getId());
                    dto.setUsername(u.getUsername());
                    dto.setNickname(u.getNickname());
                    dto.setAvatarUrl(u.getAvatarUrl());
                    return dto;
                }).collect(Collectors.toList());
    }

    private Friendship findFriendship(Long userId1, Long userId2) {
        Friendship f = friendshipMapper.selectOne(
                new LambdaQueryWrapper<Friendship>()
                        .eq(Friendship::getRequesterId, userId1)
                        .eq(Friendship::getAddresseeId, userId2));
        if (f != null) return f;
        return friendshipMapper.selectOne(
                new LambdaQueryWrapper<Friendship>()
                        .eq(Friendship::getRequesterId, userId2)
                        .eq(Friendship::getAddresseeId, userId1));
    }

    private FriendDTO toFriendDTO(User u, Friendship f) {
        FriendDTO dto = new FriendDTO();
        dto.setId(f.getId());
        dto.setUserId(u.getId());
        dto.setUsername(u.getUsername());
        dto.setNickname(u.getNickname());
        dto.setAvatarUrl(u.getAvatarUrl());
        dto.setStatus(f.getStatus());
        dto.setCreatedAt(f.getCreatedAt() != null
                ? f.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null);
        return dto;
    }
}
