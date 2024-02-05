package todo.remindgpt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import todo.remindgpt.model.Message;
import todo.remindgpt.model.Messages;
import todo.remindgpt.model.OpenAIPayload;
import todo.remindgpt.model.TaskInputPayload;
import todo.remindgpt.repositories.Category;
import todo.remindgpt.repositories.CategoryCacheRepository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class ClientOpenAIService {
    @Autowired
    private CategoryCacheRepository categoryCacheRepository;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private KafkaService kafkaService;

    @Autowired
    private RedisService redisService;

    @Value("${openai.content}")
    private String content;

    public ClientOpenAIService() {
    }

    public void initCategories(String[] cats){
        int partition=0;
        for(String cat: cats){
            Category category=new Category();
            category.setId(cat);
            category.setValue(partition++);
            categoryCacheRepository.save(category);
        }
        log.info("{} new categories saved", cats.length);
    }

    public String initPrompt(String tasks){
        String prompt;
        Iterable<Category> cats=categoryCacheRepository.findAll();
        StringBuffer categories=new StringBuffer();
        for(Category cat: cats){
            categories.append(cat.getId());
            categories.append(", ");
        }
        content=content.replace("[tasks]",tasks);
        prompt=content.replace("[categories]",categories.toString());
        log.info("prompt sending to openai:{}", prompt);
        HttpHeaders httpHeaders=new HttpHeaders();
        httpHeaders.setBearerAuth("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");
        OpenAIPayload openAIPayload = new OpenAIPayload();
        openAIPayload.setModel("gpt-3.5-turbo");

        Messages message = new Messages();
        message.setRole("user");
        message.setContent("Categorize tasks commit git code, plant trees, talk to a friend, water plants- each into just one of these categories self-dev, work, study and give me output in this format [task: category]");

        List<Messages> messagesList = Arrays.asList(message);
        openAIPayload.setMessages(messagesList);
        HttpEntity<OpenAIPayload> requestEntity = new HttpEntity<>(openAIPayload, httpHeaders);

        //todo: call openai api here and responde with map of task and type
        ResponseEntity<String> responseEntity=restTemplate.exchange("https://api.openai.com/v1/chat/completions", HttpMethod.POST,requestEntity, String.class);
        String jsonString=responseEntity.getBody().toString();
        ObjectMapper objectMapper=new ObjectMapper();
        String out="";
        try {
            JsonNode jsonNode=objectMapper.readTree(jsonString);
            out=jsonNode.path("choices").get(0).path("message").path("content").asText();
        }
        catch(Exception ex){

        }
        String[] taskList=out.split("\n");
        for(String task: taskList){
            String[] pair=task.split(":");
            String key=pair[1].trim();
            String value=pair[0].trim();

            Iterable<Category> allCategories=categoryCacheRepository.findAll();
            for(Category category: allCategories){
                if (key.contains(category.getId())){
                    key=category.getId();
                    break;
                }
            }


            System.out.println("pair:"+key+value);
            //todo: validate whether tasks and categories are correctly returned by openai
            //todo: send pair[0] as value and pair[1] as key to Kafka topic.
            //get partition no from redis
            //System.out.println("categoryCacheRepository: "+categoryCacheRepository.findById("self-dev").get().getPartition()); //Optional[Category(id=work, partition=3)]
            System.out.println("key: "+key);
            Optional<Category> obj=categoryCacheRepository.findById(key);
            int partition=obj.get().getValue();
            kafkaService.produce(partition,key,value);
        }
       // log.info("response:{}",responseEntity.getBody().getChoices().get(0));
        return prompt;
    }
    public String[] getCategories(){
        String[] cats=new String[(int)categoryCacheRepository.count()];
        Iterable<Category> categories= categoryCacheRepository.findAll();
        int i=0;
        for(Category c: categories){
            cats[i++]=c.getId();
        }
        return cats;
    }

    public void parseInput(String inputText){
        String[] redisCats;
        String[] inputs=inputText.split(";");
        Message messages=new Message();
        getCategories();
        content.replace("[tasks]",inputs.toString());
       // content.replace("[categories]",redisCats.toString());
        System.out.println("message"+content);
    }

    public void parseInput(TaskInputPayload taskInputPayload){
        List<String> categories=taskInputPayload.getCategories();
        if(categories.size()>0){
            redisService.saveCategory();
        }


    }


}
