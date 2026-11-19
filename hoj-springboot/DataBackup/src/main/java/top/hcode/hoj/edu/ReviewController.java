package top.hcode.hoj.edu;

import lombok.Data;
import java.util.*;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.result.CommonResult;

@RestController
@RequiresAuthentication
@RequestMapping("/api/edu/review")
public class ReviewController {
    private final ContentReview review;
    private final ClassSimilarity similarity;
    public ReviewController(ContentReview review,ClassSimilarity similarity){this.review=review;this.similarity=similarity;}
    @ExceptionHandler(EduException.class)
    public CommonResult<Void> error(EduException e){return CommonResult.errorResponse(e.getMessage(),e.getStatus());}
    @GetMapping("/queue")
    public CommonResult<List<Map<String,Object>>> queue(@RequestParam(required=false) Long classId,
            @RequestParam(defaultValue="PENDING") String state,@RequestParam(defaultValue="0") long before){
        return CommonResult.successResponse(review.queue(classId,state,before));
    }
    @Data public static class Decision { private Long classId; private String decision; }
    @PostMapping("/{id}/decision")
    public CommonResult<Boolean> decide(@PathVariable long id,@RequestBody Decision body){
        review.decide(body.getClassId(),id,body.getDecision()); return CommonResult.successResponse(true);
    }
    @GetMapping("/rules")
    public CommonResult<Map<String,Object>> rules(){return CommonResult.successResponse(review.settings());}
    @Data public static class Dictionary { private long revision; private String words; }
    @PutMapping("/rules")
    public CommonResult<Boolean> rules(@RequestBody Dictionary body){
        review.configure(body.getRevision(),body.getWords()); return CommonResult.successResponse(true);
    }
    @GetMapping("/similarity")
    public CommonResult<Map<String,Object>> scan(@RequestParam long classId,@RequestParam long pid,
            @RequestParam String family,@RequestParam(defaultValue="true") boolean ac,@RequestParam(defaultValue="75") int threshold){
        return CommonResult.successResponse(similarity.compare(classId,pid,family,ac,threshold));
    }
}
