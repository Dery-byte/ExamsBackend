package com.exam.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "system_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SystemSetting {

    @Id
    private String settingKey;

    /** TEXT, not the default VARCHAR(255): some values are longer (subscription keys ~450 characters). */
    @Column(columnDefinition = "TEXT")
    private String settingValue;

    public SystemSetting(String settingKey) {
        this.settingKey = settingKey;
    }
}
