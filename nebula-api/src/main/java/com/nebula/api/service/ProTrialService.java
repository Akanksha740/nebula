package com.nebula.api.service;

import com.nebula.api.repository.CustomerRepository;
import com.nebula.common.entity.Customer;
import com.nebula.common.exception.NebulaException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProTrialService {

    private static final Logger log = LoggerFactory.getLogger(ProTrialService.class);
    private static final int TRIAL_DAYS = 7;

    private static final Set<String> ELIGIBLE_EMAILS = Set.of(
            "thevirusthere123@gmail.com", "wildkong@protonmail.com", "andremrtc@gmail.com",
            "freshfitscentral@gmail.com", "fruitkornuit@gmail.com", "i.kashish31@gmail.com",
            "chenfenghua2016@gmail.com", "dlw_wlb@hotmail.com", "zhaojieqazwsx@gmail.com",
            "ilias.pittas1@gmail.com", "haithanhhh3@gmail.com", "alpcanarslan95@gmail.com",
            "xfddwhh@gmail.com", "abhijaipur2011@gmail.com", "yair319732@gmail.com",
            "petitkanediallo@gmail.com", "yairelmaliah31973232@gmail.com", "scantarajr@gmail.com",
            "zakariyabeg@gmail.com", "anurag.s996889@gmail.com", "diyuan.dai@gmail.com",
            "jeremy.lecat@gmail.com", "lamqt710@gmail.com", "cyansce@gmail.com",
            "oprakash.nitp@gmail.com", "omprakash.nitp.1996@gmail.com"
    );

    private final CustomerRepository customerRepository;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public Customer activateProTrial(Customer customer) {
        String email = customer.getEmail().toLowerCase();

        if (!ELIGIBLE_EMAILS.contains(email)) {
            throw new NebulaException("You are not eligible for a free Pro trial", "TRIAL_NOT_ELIGIBLE", 403);
        }

        if (customer.getTier() == Customer.SubscriptionTier.PRO
                || customer.getTier() == Customer.SubscriptionTier.PRO_TRIAL
                || customer.getTier() == Customer.SubscriptionTier.ENTERPRISE) {
            throw new NebulaException("You already have an active Pro or Enterprise subscription", "TRIAL_ALREADY_SUBSCRIBED", 400);
        }

        if (customer.getProTrialExpiresAt() != null) {
            throw new NebulaException("You have already used your free Pro trial", "TRIAL_ALREADY_USED", 400);
        }

        Instant trialEnd = Instant.now().plus(TRIAL_DAYS, ChronoUnit.DAYS);
        customer.setTier(Customer.SubscriptionTier.PRO_TRIAL);
        customer.setProTrialExpiresAt(trialEnd);
        customerRepository.save(customer);

        clearRateLimitCache(customer.getId());

        log.info("Activated Pro trial for customer {} until {}", customer.getEmail(), trialEnd);
        return customer;
    }

    @Scheduled(fixedRate = 3600000) // every hour
    @Transactional
    public void expireTrials() {
        List<Customer> expired = customerRepository.findExpiredTrials(Instant.now());
        for (Customer customer : expired) {
            customer.setTier(Customer.SubscriptionTier.STARTER);
            customerRepository.save(customer);
            clearRateLimitCache(customer.getId());
            log.info("Pro trial expired for customer {}", customer.getEmail());
        }
        if (!expired.isEmpty()) {
            log.info("Expired {} Pro trial(s)", expired.size());
        }
    }

    private void clearRateLimitCache(java.util.UUID customerId) {
        try {
            String pattern = "rate_limit:" + customerId + ":*";
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Failed to clear rate-limit cache for customer {}: {}", customerId, e.getMessage());
        }
    }
}
