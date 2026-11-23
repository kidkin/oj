package top.hcode.hoj.pojo.dto;

import lombok.Data;

@Data
public class PaperCodeJob {
    private String token;
    private long id;
    private long pid;
    private String uid;
    private String language;
    private String code;
    private boolean grading;
}
