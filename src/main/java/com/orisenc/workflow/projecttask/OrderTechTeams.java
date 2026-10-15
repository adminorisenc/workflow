package com.orisenc.workflow.projecttask;

import com.fasterxml.jackson.databind.JsonNode;
import com.orisenc.security.*;
import com.orisenc.workflow.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.time.Duration;
import java.util.*;

@Component
public class OrderTechTeams {
  private final RestClient hr;
  public OrderTechTeams(RestClient.Builder builder,OrisencSecurityProperties security,
      OrisencAuthorizationProperties auth,
      @Value("${orisenc.order-tech.hr-url:http://localhost:8086/hr-service/ws}")String url,
      @Value("${orisenc.order-tech.hr-scope:}")String scope) {
    if(scope.isBlank()||!auth.serviceIdentityConfigured()){hr=null;return;}
    var http=builder.clone().requestFactory(ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
        .withConnectTimeout(Duration.ofSeconds(5)).withReadTimeout(Duration.ofSeconds(10)))).build();
    var tokens=new ServiceTokenProvider(auth.clientId(),auth.clientSecret(),scope,security.tokenUri(),Duration.ofMinutes(2),http);
    hr=http.mutate().baseUrl(url).requestInterceptor((request,body,execution)->{
      request.getHeaders().setBearerAuth(tokens.accessToken());var result=execution.execute(request,body);
      if(result.getStatusCode().value()==401)tokens.invalidate();return result;
    }).build();
  }
  public record Members(String name,List<String> usernames) {}
  public Members members(long id) {
    if(hr==null)throw unavailable("HR team connection is not configured.");
    try {
      var team=hr.get().uri("/api/teams/"+id).retrieve().body(JsonNode.class);
      if(team==null)throw unavailable("HR returned incomplete team details.");
      var names=new LinkedHashSet<String>();
      for(int page=0;;page++) {
        var rows=hr.get().uri("/api/teams/"+id+"/members?activeOnly=true&page="+page+"&size=100").retrieve().body(JsonNode.class);
        if(rows==null||!rows.path("content").isArray())throw unavailable("HR returned incomplete membership.");
        for(var member:rows.path("content"))if(member.path("active").asBoolean()) {
          String username=member.path("employee").path("userPrincipalName").asText();
          if(username.isBlank())throw ApiException.badRequest("A team member has no user identity.");names.add(username.toLowerCase(Locale.ROOT));
        }
        if(page+1>=rows.path("totalPages").asInt())break;
      }
      return new Members(team.path("name").asText(),List.copyOf(names));
    } catch(HttpClientErrorException.NotFound e){throw ApiException.badRequest("The assignment team was not found.");}
    catch(RestClientException e){throw unavailable("Team membership could not be verified.");}
  }
  private static ApiException unavailable(String message){return new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,message);}
}
