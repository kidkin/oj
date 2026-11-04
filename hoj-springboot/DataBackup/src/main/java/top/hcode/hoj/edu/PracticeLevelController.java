package top.hcode.hoj.edu;

import lombok.Data;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.result.CommonResult;
import java.util.*;

@RestController
@RequestMapping("/api")
public class PracticeLevelController {
    private final PracticeLevelService service;
    public PracticeLevelController(PracticeLevelService service) { this.service = service; }
    @Data public static class UsersInput { private List<String> uids; private List<String> usernames; }
    @PostMapping("/user-practice-levels")
    public CommonResult<List<Map<String, Object>>> users(@RequestBody UsersInput input) {
        if (input == null) throw new EduException(400, "请提供需要查询的用户");
        return CommonResult.successResponse(service.users(input.getUids(), input.getUsernames()));
    }
    @GetMapping("/practice-level-rules")
    public CommonResult<List<Map<String, Object>>> rules() { return CommonResult.successResponse(service.rules()); }
    @ExceptionHandler(EduException.class)
    public CommonResult<Void> error(EduException error) { return CommonResult.errorResponse(error.getMessage(), error.getStatus()); }
}
