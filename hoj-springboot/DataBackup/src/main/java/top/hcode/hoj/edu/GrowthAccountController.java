package top.hcode.hoj.edu;

import lombok.Data;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresRoles;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.result.CommonResult;
import top.hcode.hoj.shiro.AccountProfile;

import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class GrowthAccountController {
    private final GrowthAccountService growth;

    public GrowthAccountController(GrowthAccountService growth) { this.growth = growth; }

    private String currentUid() {
        return ((AccountProfile) SecurityUtils.getSubject().getPrincipal()).getUid();
    }

    @GetMapping("/my-growth-points")
    @RequiresAuthentication
    public CommonResult<Map<String, Object>> myPoints(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return CommonResult.successResponse(growth.wallet(currentUid()));
    }

    @GetMapping("/admin/growth-points/{uid}")
    @RequiresAuthentication
    @RequiresRoles("root")
    public CommonResult<Map<String, Object>> accountPoints(@PathVariable String uid, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return CommonResult.successResponse(growth.wallet(uid));
    }

    @Data
    public static class ReviewInput {
        private Boolean invalid;
        private String reason;
    }

    @PutMapping("/admin/growth-submission-review/{submitId}")
    @RequiresAuthentication
    @RequiresRoles("root")
    public CommonResult<Void> review(@PathVariable long submitId, @RequestBody ReviewInput input) {
        if (submitId <= 0 || input == null || input.getInvalid() == null) throw new EduException(400, "无效的提交标记请求");
        growth.review(submitId, input.getInvalid(), input.getReason(), currentUid());
        return CommonResult.successResponse();
    }

    @ExceptionHandler(EduException.class)
    public CommonResult<Void> error(EduException error) {
        return CommonResult.errorResponse(error.getMessage(), error.getStatus());
    }
}
