package top.hcode.hoj.edu;

import lombok.Data;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.result.CommonResult;
import java.util.*;

@RestController
@RequiresAuthentication
@RequestMapping("/api/edu/classes/{classId}/progress")
public class EduClassProgressController {
    private final EduClassProgressService service;
    public EduClassProgressController(EduClassProgressService service) { this.service=service; }
    @Data public static class ProgressInput { private List<Long> problemIds; }
    @ExceptionHandler(EduException.class) public CommonResult<Void> error(EduException error) { return CommonResult.errorResponse(error.getMessage(),error.getStatus()); }
    @GetMapping("/problems") public CommonResult<Map<String,Object>> problems(@PathVariable long classId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int limit,
            @RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Long courseId) {
        return CommonResult.successResponse(service.problems(classId,page,limit,keyword,courseId));
    }
    @PostMapping public CommonResult<Map<String,Object>> generate(@PathVariable long classId,@RequestBody ProgressInput input) {
        return CommonResult.successResponse(service.generate(classId,input.getProblemIds()));
    }
}
