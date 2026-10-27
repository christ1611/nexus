package com.nextasean.wawebhook.admin

import com.nextasean.wawebhook.risk.FraudNetworkService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody

/** The fraud network (rings of applications sharing an ID number, phone or name) for the dashboard and the review page. */
@Controller
@RequestMapping("/admin/fraud-network")
class FraudNetworkController(private val network: FraudNetworkService) {
    @GetMapping
    @ResponseBody
    fun network(): FraudNetworkService.Network = network.network()
}
