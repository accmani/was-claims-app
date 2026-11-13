package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Claim;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/**
 * REST controller for ClaimProcessingBean.
 * Used by external systems accessing this service via HTTP.
 */
@RestController
@RequestMapping("/claims")
public class ClaimProcessingController {

    private final ClaimProcessingRemote claimProcessingRemote;

    public ClaimProcessingController(ClaimProcessingRemote claimProcessingRemote) {
        this.claimProcessingRemote = claimProcessingRemote;
    }

    @PostMapping("/submit")
    @Transactional
    public ResponseEntity<Claim> submitClaim(@RequestBody Claim claim, @RequestParam String submittedBy) {
        Claim submittedClaim = claimProcessingRemote.submitClaim(claim, submittedBy);
        return new ResponseEntity<>(submittedClaim, HttpStatus.CREATED);
    }

    @PostMapping("/appeal")
    @Transactional
    public ResponseEntity<Claim> appealClaim(@RequestParam Long originalClaimId, @RequestParam String appealReason, @RequestParam String appealedBy) {
        Claim appealedClaim = claimProcessingRemote.appealClaim(originalClaimId, appealReason, appealedBy);
        return new ResponseEntity<>(appealedClaim, HttpStatus.OK);
    }

    @GetMapping("/member/{memberId}")
    public ResponseEntity<List<Claim>> getClaimsForMember(@PathVariable String memberId, @RequestParam int page, @RequestParam int pageSize) {
        List<Claim> claims = claimProcessingRemote.getClaimsForMember(memberId, page, pageSize);
        return new ResponseEntity<>(claims, HttpStatus.OK);
    }

    @GetMapping("/pending")
    public ResponseEntity<List<Claim>> findPendingClaimsOlderThan(@RequestParam int days) {
        List<Claim> pendingClaims = claimProcessingRemote.findPendingClaimsOlderThan(days);
        return new ResponseEntity<>(pendingClaims, HttpStatus.OK);
    }
}
