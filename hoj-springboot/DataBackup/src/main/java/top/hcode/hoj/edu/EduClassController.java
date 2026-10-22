package top.hcode.hoj.edu;

import lombok.Data;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.result.CommonResult;

import java.util.List;
import java.util.Map;

@RestController
@RequiresAuthentication
@RequestMapping("/api/edu")
public class EduClassController {
    private final EduClassService service;
    public EduClassController(EduClassService service) { this.service = service; }

    @Data public static class ClassInput { private String name; private String description = ""; private int status; private String displayId; }
    @Data public static class MembersInput { private List<String> usernames; private String role = "STUDENT"; }
    @Data public static class TeacherInput { private String username; }

    @ExceptionHandler(EduException.class)
    public CommonResult<Void> error(EduException error) { return CommonResult.errorResponse(error.getMessage(), error.getStatus()); }

    @GetMapping("/me") public CommonResult<Map<String, Object>> me() { return CommonResult.successResponse(service.me()); }
    @GetMapping("/classes") public CommonResult<Map<String, Object>> classes(
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int limit,
            @RequestParam(defaultValue="") String keyword) { return CommonResult.successResponse(service.classes(page, limit, keyword)); }
    @PostMapping("/classes") public CommonResult<Map<String, Object>> create(@RequestBody ClassInput input) {
        return CommonResult.successResponse(service.create(input.getName(), input.getDescription(), EduDisplayOrder.parse(input.getDisplayId())));
    }
    @GetMapping("/classes/{id}") public CommonResult<Map<String, Object>> detail(@PathVariable long id) { return CommonResult.successResponse(service.detail(id)); }
    @PutMapping("/classes/{id}") public CommonResult<Void> update(@PathVariable long id, @RequestBody ClassInput input) {
        service.update(id, input.getName(), input.getDescription(), input.getStatus(), EduDisplayOrder.parse(input.getDisplayId())); return CommonResult.successResponse();
    }
    @GetMapping("/classes/{id}/members") public CommonResult<Map<String, Object>> members(@PathVariable long id,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int limit) {
        return CommonResult.successResponse(service.members(id, page, limit));
    }
    @PostMapping("/classes/{id}/members") public CommonResult<Void> add(@PathVariable long id, @RequestBody MembersInput input) {
        service.addMembers(id, input.getUsernames(), input.getRole()); return CommonResult.successResponse();
    }
    @DeleteMapping("/classes/{id}/members/{uid}") public CommonResult<Void> remove(@PathVariable long id, @PathVariable String uid) {
        service.removeMember(id, uid); return CommonResult.successResponse();
    }
    @GetMapping("/teachers") public CommonResult<List<Map<String, Object>>> teachers() { return CommonResult.successResponse(service.teachers()); }
    @PostMapping("/teachers") public CommonResult<Void> grant(@RequestBody TeacherInput input) {
        service.grantTeacher(input.getUsername()); return CommonResult.successResponse();
    }
    @DeleteMapping("/teachers/{uid}") public CommonResult<Void> revoke(@PathVariable String uid) {
        service.revokeTeacher(uid); return CommonResult.successResponse();
    }
}
