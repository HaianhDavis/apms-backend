import org.springframework.ai.chat.messages.AssistantMessage;
public class test_message {
    public static void main(String[] args) {
        AssistantMessage msg = new AssistantMessage("test");
        System.out.println(msg.getText());
    }
}
