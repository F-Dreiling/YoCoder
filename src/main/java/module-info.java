module dev.dreiling.YoCoder {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.net.http;
    requires com.fasterxml.jackson.databind;

    opens dev.dreiling.YoCoder to javafx.fxml;
    opens dev.dreiling.YoCoder.controller to javafx.fxml;
    opens dev.dreiling.YoCoder.service to javafx.fxml;

    exports dev.dreiling.YoCoder;
}
