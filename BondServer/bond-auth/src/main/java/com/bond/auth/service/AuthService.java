package com.bond.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.auth.mapper.UserMapper;
import com.bond.common.dto.UserDTO;
import com.bond.common.entity.User;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.JwtUtils;
import com.bond.common.utils.SnowflakeId;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final ConfigService configService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserDTO register(String username, String password, String nickname) {
        if (!configService.getBoolean("user.allow_register", true)) {
            throw new BusinessException(403, "暂不允许注册");
        }
        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (existing != null) {
            throw new BusinessException(400, "用户名已存在");
        }

        User user = new User();
        user.setId(SnowflakeId.nextId());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname != null ? nickname : username);
        user.setStatus(1);
        userMapper.insert(user);

        return buildDTO(user);
    }

    public UserDTO login(String username, String password) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        return buildDTO(user);
    }

    public UserDTO refreshToken(String refreshToken) {
        if (!JwtUtils.isTokenValid(refreshToken)) {
            throw new BusinessException(401, "Refresh Token已过期");
        }
        Long userId = JwtUtils.getUserId(refreshToken);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return buildDTO(user);
    }

    public UserDTO getCurrentUser(String token) {
        Long userId = JwtUtils.getUserId(token);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setNickname(user.getNickname());
        dto.setAvatarUrl(user.getAvatarUrl());
        return dto;
    }

    public UserDTO updateProfile(String token, String nickname, String avatarUrl) {
        Long userId = JwtUtils.getUserId(token);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (nickname != null) user.setNickname(nickname);
        if (avatarUrl != null) user.setAvatarUrl(avatarUrl);
        userMapper.updateById(user);
        return getCurrentUser(token);
    }

    private UserDTO buildDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setNickname(user.getNickname());
        dto.setAvatarUrl(user.getAvatarUrl());
        dto.setAccessToken(JwtUtils.generateAccessToken(user.getId(), user.getUsername()));
        dto.setRefreshToken(JwtUtils.generateRefreshToken(user.getId()));
        return dto;
    }
}
