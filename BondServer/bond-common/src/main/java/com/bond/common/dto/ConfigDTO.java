package com.bond.common.dto;

import lombok.Data;

@Data
public class ConfigDTO {
    private String key;
    private String value;
    private String type;
    private String description;
}
