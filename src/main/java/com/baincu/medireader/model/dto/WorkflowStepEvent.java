package com.baincu.medireader.model.dto;

import com.baincu.medireader.model.enums.WorkflowStep;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStepEvent {

    private WorkflowStep step;
    private String status;
    private String message;
    private Object data;
    private String token;
}
