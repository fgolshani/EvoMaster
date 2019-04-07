package org.evomaster.core.problem.rest.service

import com.google.inject.Inject
import org.evomaster.core.EMConfig
import org.evomaster.core.remote.service.RemoteController
import org.glassfish.jersey.client.ClientConfig
import org.glassfish.jersey.client.ClientProperties
import org.glassfish.jersey.client.HttpUrlConnectorProvider
import javax.ws.rs.client.Client
import javax.ws.rs.client.ClientBuilder

class RestService{
    @Inject
    private lateinit var rc: RemoteController

    @Inject
    protected lateinit var configuration: EMConfig
}