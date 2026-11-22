package top.hcode.hoj.edu;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.result.CommonResult;

@RestController
@RequiresAuthentication
@RequestMapping("/api/edu/contest-paper/{cid}")
public class ContestPaperController {
    private final ContestPaperService service;
    public ContestPaperController(ContestPaperService service) {this.service=service;}
    @ExceptionHandler(EduException.class)
    public CommonResult<Void> error(EduException e) {return CommonResult.errorResponse(e.getMessage(),e.getStatus());}
    @GetMapping public CommonResult<?> state(@PathVariable long cid) {return CommonResult.successResponse(service.state(cid));}
    @PostMapping("/start") public CommonResult<?> start(@PathVariable long cid) {return CommonResult.successResponse(service.start(cid));}
    @PutMapping("/draft") public CommonResult<?> draft(@PathVariable long cid,@RequestBody JsonNode data) {return CommonResult.successResponse(service.save(cid,data,false));}
    @PostMapping("/submit") public CommonResult<?> submit(@PathVariable long cid,@RequestBody JsonNode data) {return CommonResult.successResponse(service.save(cid,data,true));}
    @GetMapping("/settings") public CommonResult<?> settings(@PathVariable long cid) {return CommonResult.successResponse(service.settings(cid));}
    @PutMapping("/settings") public CommonResult<?> settings(@PathVariable long cid,@RequestBody JsonNode data) {service.configure(cid,data);return CommonResult.successResponse();}
    @GetMapping("/results") public CommonResult<?> results(@PathVariable long cid) {return CommonResult.successResponse(service.results(cid));}
}
