package dev.openchoreo.claims;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/claims")
public class ClaimController {

    private static final Logger log = LoggerFactory.getLogger(ClaimController.class);

    private final ClaimRepository repository;

    public ClaimController(ClaimRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Claim> listAll() {
        log.debug("Received request to list all claims");
        List<Claim> claims = repository.findAll();
        log.info("Listed {} claim(s)", claims.size());
        return claims;
    }

    @GetMapping("/{id}")
    public Claim getById(@PathVariable Long id) {
        log.debug("Received request to fetch claim id={}", id);
        return repository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Claim id={} not found", id);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim not found");
                });
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Claim create(@RequestBody Claim claim) {
        log.debug("Received request to create claim policy={} claimant={}", claim.getPolicyNumber(), claim.getClaimantName());
        try {
            Claim saved = repository.save(claim);
            log.info("Created claim id={} policy={} claimant={} amount={} status={}",
                    saved.getId(), saved.getPolicyNumber(), saved.getClaimantName(),
                    saved.getAmount(), saved.getStatus());
            return saved;
        } catch (Exception e) {
            log.error("Failed to create claim policy={} claimant={} error={}",
                    claim.getPolicyNumber(), claim.getClaimantName(), e.getMessage(), e);
            throw e;
        }
    }

    @PatchMapping("/{id}/status")
    public Claim updateStatus(@PathVariable Long id, @RequestParam Claim.ClaimStatus status) {
        log.debug("Received request to update status of claim id={} to {}", id, status);
        Claim claim = repository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Claim id={} not found for status update", id);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim not found");
                });
        try {
            Claim.ClaimStatus previous = claim.getStatus();
            claim.setStatus(status);
            Claim updated = repository.save(claim);
            log.info("Updated claim id={} status={} -> {}", id, previous, status);
            return updated;
        } catch (Exception e) {
            log.error("Failed to update status of claim id={} error={}", id, e.getMessage(), e);
            throw e;
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        log.debug("Received request to delete claim id={}", id);
        if (!repository.existsById(id)) {
            log.warn("Claim id={} not found for deletion", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim not found");
        }
        try {
            repository.deleteById(id);
            log.info("Deleted claim id={}", id);
        } catch (Exception e) {
            log.error("Failed to delete claim id={} error={}", id, e.getMessage(), e);
            throw e;
        }
    }
}
