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
public class EduCourseController {
    private final EduCourseService service;
    public EduCourseController(EduCourseService service) { this.service = service; }
    @Data public static class CourseInput { private String title; private String description = ""; private int status; private Long revision; }
    @Data public static class ProblemsInput { private List<Long> problemIds; private Long revision; }
    @Data public static class ReferencesInput { private List<Long> courseIds; }
    @ExceptionHandler(EduException.class) public CommonResult<Void> error(EduException error) { return CommonResult.errorResponse(error.getMessage(), error.getStatus()); }

    @GetMapping("/courses") public CommonResult<Map<String, Object>> list(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int limit,
            @RequestParam(defaultValue="") String keyword, @RequestParam(defaultValue="library") String scope, @RequestParam(defaultValue="false") boolean publishedOnly) {
        return CommonResult.successResponse(service.list(page, limit, keyword, scope, publishedOnly));
    }
    @PostMapping("/courses") public CommonResult<Map<String, Object>> create(@RequestBody CourseInput input) { return CommonResult.successResponse(service.create(input.getTitle(), input.getDescription())); }
    @GetMapping("/courses/{id}") public CommonResult<Map<String, Object>> detail(@PathVariable long id) { return CommonResult.successResponse(service.detail(id)); }
    @PutMapping("/courses/{id}") public CommonResult<Map<String, Object>> update(@PathVariable long id, @RequestBody CourseInput input) {
        return CommonResult.successResponse(service.update(id, input.getTitle(), input.getDescription(), input.getStatus(), input.getRevision()));
    }
    @PutMapping("/courses/{id}/problems") public CommonResult<Map<String, Object>> problems(@PathVariable long id, @RequestBody ProblemsInput input) {
        return CommonResult.successResponse(service.replaceProblems(id, input.getProblemIds(), input.getRevision()));
    }
    @GetMapping("/public-problems") public CommonResult<Map<String, Object>> picker(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int limit, @RequestParam(defaultValue="") String keyword) {
        return CommonResult.successResponse(service.publicProblems(page, limit, keyword));
    }
    @GetMapping("/classes/{id}/courses") public CommonResult<Map<String, Object>> classCourses(@PathVariable long id, @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int limit) {
        return CommonResult.successResponse(service.classCourses(id, page, limit));
    }
    @PostMapping("/classes/{id}/courses") public CommonResult<Void> attach(@PathVariable long id, @RequestBody ReferencesInput input) { service.attach(id, input.getCourseIds()); return CommonResult.successResponse(); }
    @DeleteMapping("/classes/{id}/courses/{courseId}") public CommonResult<Void> detach(@PathVariable long id, @PathVariable long courseId) { service.detach(id, courseId); return CommonResult.successResponse(); }
}
