package dev.dreiling.YoCoder;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class YoCoderApplication extends Application {

	@Override
	public void start(Stage primaryStage) throws Exception {
		FXMLLoader loader = new FXMLLoader( getClass().getResource("/dev/dreiling/YoCoder/main.fxml") );

		Scene scene = new Scene(loader.load(), 1280, 820);
		scene.getStylesheets().add( getClass().getResource("/dev/dreiling/YoCoder/style.css").toExternalForm() );

		Image image = new Image(this.getClass().getResourceAsStream("/yocoder.png"));
		primaryStage.getIcons().add(image);
		primaryStage.setTitle("YoCoder");
		primaryStage.setScene(scene);
		primaryStage.setMinWidth(900);
		primaryStage.setMinHeight(600);
		primaryStage.show();
	}

	public static void main(String[] args) {
		launch(args);
	}
}